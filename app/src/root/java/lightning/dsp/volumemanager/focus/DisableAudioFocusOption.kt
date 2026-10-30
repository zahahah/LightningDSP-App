package lightning.dsp.volumemanager.focus

import android.content.Context
import com.topjohnwu.superuser.Shell
import lightning.dsp.flavor.RootShellImpl

/** Opt-in request; Vector/Xposed activation and System Framework scope remain external. */
object DisableAudioFocusOption {
    private const val PREFERENCES = "lightningdsp_volume_panel"
    private const val KEY = "disable_audio_focus"
    private const val PROPERTY = "debug.lightningdsp.disable_audio_focus"
    private const val READY = "debug.lightningdsp.audio_focus_hook_pid"

    fun requested(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** The hook itself must acknowledge this live system_server process. */
    fun available(): Boolean {
        if (!RootShellImpl.hasRootShell()) return false
        val result = Shell.cmd("getprop $READY", "pidof system_server").exec()
        val marker = result.out.getOrNull(0)?.trim()
        val server = result.out.getOrNull(1)?.trim()
        return result.isSuccess && !marker.isNullOrEmpty() && marker == server
    }

    fun active(): Boolean {
        if (!available()) return false
        val result = Shell.cmd("getprop $PROPERTY").exec()
        return result.isSuccess && result.out.lastOrNull()?.trim() == "1"
    }

    /** Must run off the UI thread. Persist after the root property is verified. */
    fun set(context: Context, enabled: Boolean): Boolean {
        if (!RootShellImpl.hasRootShell()) return false
        val value = if (enabled) "1" else "0"
        val result = Shell.cmd("setprop $PROPERTY $value", "getprop $PROPERTY").exec()
        if (!result.isSuccess || result.out.lastOrNull()?.trim() != value) return false
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        return true
    }

    fun restore(context: Context) {
        if (requested(context)) set(context, true)
    }
}
