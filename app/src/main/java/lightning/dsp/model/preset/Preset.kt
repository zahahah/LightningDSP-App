package lightning.dsp.model.preset

import android.content.Context
import android.content.Intent
import lightning.dsp.BuildConfig
import lightning.dsp.R
import lightning.dsp.backup.BackupManager
import lightning.dsp.liveprog.EelParser
import lightning.dsp.interop.DspDesiredState
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.broadcastPresetLoadEvent
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import lightning.dsp.utils.storage.Tar
import lightning.dsp.utils.storage.LibraryStorage
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class Preset(val name: String, externalPath: File? = null): KoinComponent {
    private val ctx: Context by inject()
    private val externalPath = externalPath ?: ctx.getExternalFilesDir(null)?.let { File(it, "Presets") }
        ?: throw IOException("App storage is unavailable. Retry after storage is mounted.")

    fun file(): File = File(externalPath, name)

    fun rename(newName: String): Boolean {
        return file().renameTo(File(externalPath, newName))
    }

    fun validate(): Boolean {
        return validate(FileInputStream(file()))
    }

    /**
     * @exception Exception if preset cannot be loaded
     */
    fun load(): PresetMetadata {
        val file = file()
        Timber.d("Loading preset from ${file.path}")
        return load(
            ctx,
            FileInputStream(file)
        )
    }

    var lastSaveError: String? = null
        private set

    fun save(): Boolean {
        lastSaveError = null
        var stage = "creating the preset directory"
        try {
            LibraryStorage.ensureDirectory(externalPath)
            val targetFile = file()
            LibraryStorage.replaceFile(targetFile) { stagedFile ->
                stage = "writing the preset archive"
                writeArchive(stagedFile)
                stage = "replacing the preset file"
            }
            return true
        } catch (ex: Exception) {
            lastSaveError = "Failed while $stage: ${ex.message ?: ex.javaClass.simpleName}"
            Timber.e(ex, "Preset save failed during %s", stage)
            return false
        }
    }

    private fun writeArchive(targetFile: File) {
        Tar.Composer(targetFile).use { c ->
            c.metadata = mutableMapOf(
                META_VERSION to PRESET_VERSION,
                META_APP_VERSION to BuildConfig.VERSION_NAME,
                META_APP_FLAVOR to BuildConfig.FLAVOR,
                META_LIVEPROG_INCLUDED to false.toString(),
                META_MIN_VERSION_CODE to MIN_VERSION_CODE
            )

            val preferenceRoot = currentPath(ctx)
            val extraNamespaces = preferenceRoot.listFiles()?.filter {
                it.isFile && it.name.startsWith("dsp_") && it.extension == "xml"
            }?.map { it.nameWithoutExtension }.orEmpty()
            val snapshot = File.createTempFile("preset-settings-", ".xml", ctx.cacheDir)
            try {
                (Constants.DSP_PREFERENCE_NAMESPACES + extraNamespaces).distinct().forEach { namespace ->
                    snapshot.outputStream().use { output ->
                        SharedPreferencesXml.write(ctx.getSharedPreferences(namespace, Context.MODE_PRIVATE), output)
                    }
                    check(c.add(snapshot, "$namespace.xml")) {
                        "Unable to read DSP settings for $namespace"
                    }
                }
            } finally {
                snapshot.delete()
            }

            findLiveprogScriptPath(ctx)?.let { path ->
                val liveprogFile = File(path)
                if (liveprogFile.exists()) {
                    Timber.d("Saving included liveprog script state from '$path'")

                    c.metadata[META_LIVEPROG_INCLUDED] = true.toString()
                    File(ctx.cacheDir, FILE_LIVEPROG).let {
                        liveprogFile.copyTo(it, overwrite = true)
                        c.add(it)
                    }
                }
            }
        }
    }

    companion object {
        /* Update constants as needed */
        const val PRESET_VERSION = "3"
        const val MIN_VERSION_CODE = "26"

        const val FILE_LIVEPROG = "liveprog"

        const val META_VERSION = "version"
        const val META_APP_VERSION = "app_version"
        const val META_APP_FLAVOR = "app_flavor"
        const val META_LIVEPROG_INCLUDED = "liveprog_included" /* version 2+ */
        const val META_MIN_VERSION_CODE = "min_version_code" /* version 3+ */

        private fun currentPath(ctx: Context) = File(ctx.applicationInfo.dataDir + "/shared_prefs")
        private fun isKnownEntry(n: String) = n == FILE_LIVEPROG ||
            (n.startsWith("dsp_") && n.endsWith(".xml") && '/' !in n && '\\' !in n)

        fun validate(inputStream: InputStream): Boolean {
            var containsSettings = false
            val valid = Tar.Reader(inputStream) { name ->
                val known = isKnownEntry(name)
                if (known && name.endsWith(".xml")) containsSettings = true
                known
            }.validate()
            return valid && containsSettings
        }

        /**
         * @exception Exception if preset cannot be loaded
         */
        fun load(ctx: Context, stream: InputStream): PresetMetadata {
            Timber.d("Loading preset from stream")
            val targetFolder = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "preset-").toFile()
            try {
                val metadata = Tar.Reader(stream, ::isKnownEntry).extract(targetFolder)
                    ?: throw Exception(ctx.getString(R.string.filelibrary_corrupted))
                require(metadata[BackupManager.META_IS_BACKUP]?.toBoolean() != true) {
                    ctx.getString(R.string.filelibrary_is_backup_not_preset)
                }
                val minVersionCode = metadata[META_MIN_VERSION_CODE]?.toIntOrNull() ?: 0
                require(BuildConfig.VERSION_CODE >= minVersionCode) {
                    ctx.getString(R.string.filelibrary_file_too_new)
                }

                // Parse every XML before replacing any preferences. Each load owns its staging directory.
                val files = targetFolder.listFiles().orEmpty()
                val preferenceFiles = files.filter { it.name.startsWith("dsp_") && it.extension == "xml" }
                    .associate { file ->
                        file.nameWithoutExtension to file.inputStream().use(SharedPreferencesXml::parse)
                    }
                require(preferenceFiles.isNotEmpty()) { "Preset contains no DSP settings." }
                val extraNamespaces = currentPath(ctx).listFiles().orEmpty().filter {
                    it.isFile && isKnownEntry(it.name) && it.extension == "xml"
                }.map { it.nameWithoutExtension }

                DspDesiredState.replace {
                    // Missing namespaces in older presets mean defaults, never the previous preset's effects.
                    try {
                        SharedPreferencesXml.replaceAll(
                            SharedPreferencesXml.completeSnapshot(preferenceFiles, extraNamespaces),
                            preferencesFor = { ctx.getSharedPreferences(it, Context.MODE_PRIVATE) },
                        ) {
                            if (files.any { it.name == FILE_LIVEPROG } && metadata[META_LIVEPROG_INCLUDED].toBoolean()) {
                                findLiveprogScriptPath(ctx)?.let { path ->
                                    val originalFile = File(path)
                                    val storage = ctx.getExternalFilesDir(null)
                                        ?: throw IOException("App storage is unavailable. Retry after storage is mounted.")
                                    val targetFile = File(File(storage, "Liveprog"), originalFile.name)
                                    val tempPath = File(targetFolder, FILE_LIVEPROG)
                                    check(targetFile.parentFile!!.isDirectory || targetFile.parentFile!!.mkdirs()) {
                                        "Cannot create Liveprog library directory"
                                    }
                                    if (!targetFile.exists()) {
                                        tempPath.copyTo(targetFile, overwrite = true)
                                    } else {
                                        val parser = EelParser()
                                        val parserNew = EelParser()
                                        parser.load(tempPath.absolutePath)
                                        parserNew.load(targetFile.absolutePath)
                                        parser.properties.forEach(parserNew::manipulateProperty)
                                        parserNew.save()
                                    }
                                    ctx.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_RELOAD_LIVEPROG))
                                }
                            }
                        }
                    } finally {
                        // Reconcile either the complete imported state or the restored live state on failure.
                        ctx.broadcastPresetLoadEvent()
                    }
                }
                return metadata.toMutableMap()
            } finally {
                targetFolder.deleteRecursively()
            }
        }

        private fun findLiveprogScriptPath(ctx: Context): String? {
            val relativePath = ctx.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE)
                .getString(ctx.getString(R.string.key_liveprog_file), null)
                ?.takeIf { it.isNotBlank() } ?: return null
            val storage = ctx.getExternalFilesDir(null)
                ?: throw IOException("App storage is unavailable. Retry after storage is mounted.")
            return File(storage, relativePath).absolutePath
        }
    }
}


typealias PresetMetadata = MutableMap<String, String>
