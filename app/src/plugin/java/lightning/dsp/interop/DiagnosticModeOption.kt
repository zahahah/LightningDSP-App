package lightning.dsp.interop

import android.content.Context

object DiagnosticModeOption {
    fun requested(context: Context) = false
    fun set(context: Context, enabled: Boolean) = AAudioCompatibilityResult.FAILED
    fun restore(context: Context) = true
}
