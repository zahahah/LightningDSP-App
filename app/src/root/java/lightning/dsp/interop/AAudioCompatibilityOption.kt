package lightning.dsp.interop

import android.content.Context
import com.topjohnwu.superuser.Shell
import lightning.dsp.R
import lightning.dsp.flavor.RootShellImpl
import lightning.dsp.utils.Constants

/** An opt-in request, not proof that an Xposed hook is installed or active. */
object AAudioCompatibilityOption {
    private const val PROPERTY = "debug.ldsp.aaudio_compat"

    fun requested(context: Context): Boolean =
        context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
            .getBoolean(context.getString(R.string.key_audioformat_aaudio_compat), false)

    /** All shell access belongs on a worker thread. */
    @Synchronized fun set(context: Context, enabled: Boolean): AAudioCompatibilityResult {
        val old = try { requested(context) } catch (_: Exception) { return AAudioCompatibilityResult.UNKNOWN }
        try {
            if (!RootShellImpl.hasRootShell()) return AAudioCompatibilityResult.FAILED
        } catch (_: Exception) { return AAudioCompatibilityResult.FAILED }
        return AAudioCompatibilityTransaction.apply(old, enabled, ::write) { value ->
            context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit()
                .putBoolean(context.getString(R.string.key_audioformat_aaudio_compat), value).commit()
        }
    }

    @Synchronized fun actual(): Boolean? = try {
        if (!RootShellImpl.hasRootShell()) null else {
            val result = Shell.cmd("getprop $PROPERTY").exec()
            if (!result.isSuccess) null else when (result.out.lastOrNull()?.trim().orEmpty()) {
                "", "0" -> false
                "1" -> true
                else -> null
            }
        }
    } catch (_: Exception) { null }

    /** Reconcile both enabled and disabled states after Android restarts. */
    @Synchronized fun restore(context: Context): Boolean = try {
        RootShellImpl.hasRootShell() && write(requested(context))
    } catch (_: Exception) {
        false
    }

    private fun write(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val result = Shell.cmd("setprop $PROPERTY $value", "getprop $PROPERTY").exec()
        return result.isSuccess && result.out.lastOrNull()?.trim() == value
    }
}
