package lightning.dsp.volumemanager.backend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lightning.dsp.flavor.VolumeManagerLifecycle

/** Boot resumes an already enabled mixer unless the user explicitly disabled boot startup. */
class LightningDspVolumeStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        VolumeManagerLifecycle.onBackgroundEntry(context)
    }
}
