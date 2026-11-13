package lightning.dsp.interop.appmixer

import android.content.Context

/** Fusion Mode is root-only. */
internal class AppMixerModeController(context: Context, acquireExclusive: () -> AutoCloseable?, onStatus: (String) -> Unit) : AutoCloseable {
    fun request(enable: Boolean) = Unit
    override fun close() = Unit
}
