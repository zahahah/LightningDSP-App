package lightning.dsp.volumemanager.backend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot startup is a separate user opt-in; enabling the mixer does not enable this. */
class LightningDspVolumeStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        LightningDspVolumeManager.initialize(context)
        if (LightningDspVolumeManager.startupOnBoot) LightningDspVolumeManager.resume()
    }
}
