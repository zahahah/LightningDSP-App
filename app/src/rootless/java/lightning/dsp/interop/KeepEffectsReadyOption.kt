package lightning.dsp.interop

import android.content.Context

object KeepEffectsReadyOption {
    fun requested(context: Context) = false
    fun set(context: Context, enabled: Boolean) = AAudioCompatibilityResult.FAILED
    fun restore(context: Context) = false
}
