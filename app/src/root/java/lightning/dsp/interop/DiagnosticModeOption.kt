package lightning.dsp.interop

import android.content.Context
import com.topjohnwu.superuser.Shell
import lightning.dsp.flavor.RootShellImpl
import lightning.dsp.utils.Constants
import lightning.dsp.utils.logging.DiagnosticMode

/** The marker survives reboot without changing persistent Android properties or audio state. */
object DiagnosticModeOption {
    private const val DIRECTORY = "/data/adb/ldsp-diagnostics"
    private const val MARKER = "$DIRECTORY/enabled"

    fun requested(context: Context) = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        .getBoolean(DiagnosticMode.KEY, false)

    @Synchronized fun set(context: Context, enabled: Boolean): AAudioCompatibilityResult {
        if (!runCatching { RootShellImpl.hasRootShell() }.getOrDefault(false)) return AAudioCompatibilityResult.FAILED
        return AAudioCompatibilityTransaction.apply(requested(context), enabled, ::write) { value ->
            context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit()
                .putBoolean(DiagnosticMode.KEY, value).commit()
        }
    }

    @Synchronized fun restore(context: Context): Boolean = runCatching {
        RootShellImpl.hasRootShell() && write(requested(context))
    }.getOrDefault(false)

    internal fun command(enabled: Boolean): String {
        val value = if (enabled) "1" else "0"
        val property = DiagnosticMode.PROPERTY
        val markerCommand = if (enabled)
            "mkdir -p '$DIRECTORY' && chown 0:0 '$DIRECTORY' && chmod 700 '$DIRECTORY' && " +
                "touch '$MARKER' && chown 0:0 '$MARKER' && chmod 600 '$MARKER'"
        else "rm -f '$MARKER'"
        return """
            $markerCommand &&
            setprop $property $value &&
            [ "${'$'}(getprop $property)" = "$value" ] &&
            printf 'LDSP_DIAGNOSTICS:$value\n'
        """.trimIndent()
    }

    private fun write(enabled: Boolean): Boolean {
        val result = Shell.cmd(command(enabled)).exec()
        return result.isSuccess && result.out.lastOrNull()?.trim() == "LDSP_DIAGNOSTICS:${if (enabled) 1 else 0}"
    }
}
