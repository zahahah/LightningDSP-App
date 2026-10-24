package lightning.dsp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lightning.dsp.utils.EngineUtils.toggleEnginePower
import timber.log.Timber

open class PowerStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        context ?: return

        Timber.d("Received power state broadcast")

        context.toggleEnginePower(intent.getBooleanExtra(EXTRA_ENABLED, false))
    }

    companion object {
        const val EXTRA_ENABLED = "rootlessjamesdsp.enabled"
    }
}