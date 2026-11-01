@file:Suppress("UNUSED_PARAMETER")
package lightning.dsp.interop

import android.content.Context

/** Global AudioEffect coverage recovery belongs only to the root app. */
internal class GlobalCoverageMonitor(
    context: Context,
    shouldMonitor: () -> Boolean,
    reconcile: () -> Boolean,
) : AutoCloseable {
    fun requestCheck() = Unit
    override fun close() = Unit
}
