package lightning.dsp.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import lightning.dsp.R
import lightning.dsp.activity.EngineLauncherActivity
import lightning.dsp.service.BaseAudioProcessorService
import lightning.dsp.service.RootlessAudioProcessorService
import lightning.dsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object EngineUtils : KoinComponent {
    private val preferences: Preferences.App by inject()

    private fun Context.launchService(activityStarter: (Intent) -> Unit) {
        Intent(this, EngineLauncherActivity::class.java)
            .apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            .also(activityStarter)
    }

    fun Context.toggleEnginePower(isOn: Boolean, activityStarter: (Intent) -> Unit = { startActivity(it) }) {
        // Root/plugin
        if(!isRootless()) {
            if(isRoot() && BaseAudioProcessorService.activeServices <= 0) {
                launchService(activityStarter)
            }
            preferences.set(R.string.key_powered_on, isOn)
            return
        }

        sdkAbove(Build.VERSION_CODES.Q) {
            // Rootless
            if (!isOn)
                RootlessAudioProcessorService.stop(this)
            else
                launchService(activityStarter)
        }
    }
}