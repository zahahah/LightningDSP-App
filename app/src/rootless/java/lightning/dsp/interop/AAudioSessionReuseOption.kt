package lightning.dsp.interop

import android.content.Context

object AAudioSessionReuseOption {
    fun requested(context: Context) = false
    fun set(context: Context, enabled: Boolean) = AAudioCompatibilityResult.FAILED
    fun actual(): Boolean? = null
    fun restore(context: Context) = false
}
