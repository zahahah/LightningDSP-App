package lightning.dsp.interop.appmixer

import java.io.File

/** The owning app resolves its external directory under its real Android UID.
 * The helper accepts only its verified package's canonical files directory. */
internal object AppMixerExternalPath {
    fun verify(path: String, packageName: String): File? {
        if (path.isEmpty()) return null
        require(packageName == "com.lightning.dsp")
        val file = File(path)
        require(file.isAbsolute && file.canonicalPath == file.absolutePath &&
            file.path.endsWith("/Android/data/$packageName/files")) {
            "Local DSP external directory is not an owned canonical path"
        }
        return file
    }
}
