package lightning.dsp.interop

import android.content.Context

/** Compatibility facade for existing AAudio owner-retention callers and preference migrations. */
object AAudioFocusedSessionOption {
    fun requested(context: Context): Boolean = AAudioCompatibilityOption.requested(context) &&
        AAudioSessionReuseOption.requested(context)
    fun set(context: Context, enabled: Boolean): AAudioCompatibilityResult =
        KeepEffectsReadyOption.set(context, enabled)
    fun restore(context: Context): Boolean = KeepEffectsReadyOption.restore(context)
}
