package lightning.dsp.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import lightning.dsp.utils.storage.Tar
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import lightning.dsp.volumemanager.backup.VolumeManagerBackupCodec
import lightning.dsp.volumemanager.backup.VolumeManagerBackupSettings
import java.io.File
import java.io.IOException

/** Optional full-backup section. DSP presets deliberately remain independent of the mixer. */
internal object VolumeManagerBackup {
    const val ARCHIVE_PATH = "volume_manager/preferences.properties"
    private const val MANAGER = "lightningdsp_volume_manager"
    private const val PANEL = "lightningdsp_volume_panel"

    @WorkerThread
    fun addTo(context: Context, composer: Tar.Composer) {
        LightningDspVolumeManager.initialize(context)
        // Drain earlier per-app edits before taking the snapshot; never copy cached XML files.
        val manager = LightningDspVolumeManager.snapshotSettings().get()
        val panel = context.getSharedPreferences(PANEL, Context.MODE_PRIVATE).all
        val payload = VolumeManagerBackupCodec.encode(VolumeManagerBackupSettings(
            VolumeManagerBackupCodec.filterManager(manager),
            VolumeManagerBackupCodec.filterPanel(panel),
        ))
        val staged = File.createTempFile("volume-manager-backup-", ".properties", context.cacheDir)
        try {
            staged.writeBytes(payload)
            check(composer.add(staged, ARCHIVE_PATH)) { "Could not archive Volume Manager settings" }
        } finally {
            staged.delete()
        }
    }

    /** Validate before any destructive clean restore. Missing section means an older backup. */
    @WorkerThread
    fun prepareRestore(context: Context, folder: File, dirty: Boolean): () -> Unit {
        val settings = readSettings(folder) ?: return {}
        return {
            LightningDspVolumeManager.initialize(context)
            LightningDspVolumeManager.restoreSettings {
                restorePreferences(context.getSharedPreferences(MANAGER, Context.MODE_PRIVATE), settings.manager,
                    dirty, VolumeManagerBackupCodec::isManagerSetting)
                restorePreferences(context.getSharedPreferences(PANEL, Context.MODE_PRIVATE), settings.panel,
                    dirty, VolumeManagerBackupCodec::isPanelSetting)
            }.get()
        }
    }

    internal fun readSettings(folder: File): VolumeManagerBackupSettings? {
        val file = File(folder, ARCHIVE_PATH)
        if (!file.exists()) return null
        require(file.isFile && file.length() <= VolumeManagerBackupCodec.MAX_BYTES) {
            "Invalid Volume Manager backup section"
        }
        return VolumeManagerBackupCodec.decode(file.readBytes())
    }

    internal fun restorePreferences(preferences: SharedPreferences, values: Map<String, Any>, dirty: Boolean,
                        isUserSetting: (String) -> Boolean) {
        require(values.keys.all(isUserSetting)) { "Backup contains nonportable Volume Manager settings" }
        val editor = preferences.edit()
        // Remove only portable user settings. The current cleanup ledger must stay untouched.
        if (!dirty) preferences.all.keys.filter(isUserSetting).forEach(editor::remove)
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                else -> error("Unsupported Volume Manager preference type")
            }
        }
        if (!editor.commit()) throw IOException("Could not restore Volume Manager settings")
    }
}
