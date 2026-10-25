package lightning.dsp.flavor

import android.content.Context
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import timber.log.Timber

/** Resume an existing opt-in on explicit app entry, not on arbitrary background broadcasts. */
object VolumeManagerLifecycle {
    fun onForegroundEntry(context: Context) {
        val appContext = context.applicationContext
        val enabled = appContext
            .getSharedPreferences("lightningdsp_volume_manager", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (!enabled) return

        // An optional mixer failure must not prevent the main DSP screen from opening.
        runCatching {
            LightningDspVolumeManager.initialize(appContext)
            LightningDspVolumeManager.resume()
        }.onFailure { Timber.e(it, "Could not resume LightningDSP Volume Manager") }
    }
}
