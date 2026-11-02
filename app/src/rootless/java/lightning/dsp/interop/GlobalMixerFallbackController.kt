package lightning.dsp.interop

import android.content.Context

/** The root-only Global coverage feature never starts in this flavor. */
internal class GlobalMixerFallbackController(context: Context, shouldRun: () -> Boolean) : AutoCloseable {
    fun requestCheck() = Unit
    fun preferencesChanged() = Unit
    override fun close() = Unit
}
