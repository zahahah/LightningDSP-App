package lightning.dsp.interop

import android.content.Context
import com.topjohnwu.superuser.Shell
import lightning.dsp.R
import lightning.dsp.flavor.RootShellImpl
import lightning.dsp.utils.Constants

/** Only opts in to focused idle retention. It never selects or creates an audio session. */
object AAudioFocusedSessionOption {
    private const val PROPERTY = "debug.ldsp.aaudio_focus_hold"
    fun requested(context: Context): Boolean = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        .getBoolean(context.getString(R.string.key_audioformat_aaudio_focus_hold), false)

    @Synchronized fun set(context: Context, enabled: Boolean): AAudioCompatibilityResult {
        if (enabled && (!sessionMode(context) || !AAudioCompatibilityOption.requested(context) || !AAudioSessionReuseOption.requested(context)))
            return AAudioCompatibilityResult.FAILED
        val old = requested(context)
        try { if (!RootShellImpl.hasRootShell()) return AAudioCompatibilityResult.FAILED }
        catch (_: Exception) { return AAudioCompatibilityResult.FAILED }
        return AAudioCompatibilityTransaction.apply(old, enabled, ::write) { value ->
            context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit()
                .putBoolean(context.getString(R.string.key_audioformat_aaudio_focus_hold), value).commit()
        }.also { (context.applicationContext as? lightning.dsp.MainApplication)
            ?.rootSessionDatabase?.reconcileUnifiedSessionRetention() }
    }

    @Synchronized fun restore(context: Context): Boolean = try {
        RootShellImpl.hasRootShell() && write(sessionMode(context) && requested(context) && AAudioCompatibilityOption.requested(context) &&
            AAudioSessionReuseOption.requested(context))
    } catch (_: Exception) { false }

    private fun sessionMode(context: Context): Boolean = !context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        .getBoolean(context.getString(R.string.key_audioformat_processing), true)

    private fun write(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val result = Shell.cmd("setprop $PROPERTY $value", "getprop $PROPERTY").exec()
        return result.isSuccess && result.out.lastOrNull()?.trim() == value
    }
}
