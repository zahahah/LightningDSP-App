package lightning.dsp.receiver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import lightning.dsp.BuildConfig
import lightning.dsp.service.RootAudioProcessorService
import lightning.dsp.session.rootless.RootlessSessionDatabase
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import lightning.dsp.utils.isPlugin
import lightning.dsp.utils.isRoot
import timber.log.Timber

open class SessionReceiver : BroadcastReceiver() {

    @SuppressLint("BinaryOperationInTimber")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (isPlugin() || intent == null || context == null) {
            return
        }

        if(intent.getIntExtra(RootlessSessionDatabase.EXTRA_IGNORE, 0) == 1) {
            Timber.d("Control close intent ignored")
            return
        }

        Timber.i(
            "Action: ${intent.action}; " +
                    "session: ${intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)}; " +
                    "package ${intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)}")

        context.sendLocalBroadcast(
            Intent(Constants.ACTION_SESSION_CHANGED)
                .apply {
                    putExtras(intent)
                }
        )

        if(isRoot()) {
            Intent(context, me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService::class.java)
                .apply { this.action = intent.action }
                .apply { putExtras(intent) }
                .run { RootAudioProcessorService.startService(context, this) }
        }
    }
}