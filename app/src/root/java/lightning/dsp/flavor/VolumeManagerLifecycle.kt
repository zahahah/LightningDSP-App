package lightning.dsp.flavor

import android.content.Context
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import lightning.dsp.volumemanager.backend.VolumeManagerStartupPolicy
import timber.log.Timber

/** Resume an enabled mixer on app entry or the authorized boot/late-load service path. */
object VolumeManagerLifecycle {
    fun onForegroundEntry(context: Context) = resume(context, background = false)

    fun onBackgroundEntry(context: Context) = resume(context, background = true)

    private fun resume(context: Context, background: Boolean) {
        val appContext = context.applicationContext
        val preferences = appContext
            .getSharedPreferences("lightningdsp_volume_manager", Context.MODE_PRIVATE)
        val enabled = preferences.getBoolean("enabled", false)
        if (!enabled) return
        if (background && !VolumeManagerStartupPolicy.shouldResume(enabled,
                preferences.getBoolean("startup_on_boot", VolumeManagerStartupPolicy.DEFAULT_STARTUP_ON_BOOT))) return

        // An optional mixer failure must not prevent the DSP screen or service from starting.
        runCatching {
            LightningDspVolumeManager.initialize(appContext)
            LightningDspVolumeManager.resume()
        }.onFailure { Timber.e(it, "Could not resume LightningDSP Volume Manager") }
    }
}
