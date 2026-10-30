@file:Suppress("UNUSED_PARAMETER")

package lightning.dsp.flavor

/** Crash-report hooks are retained as no-ops for source compatibility. */
object CrashlyticsImpl {
    fun setCollectionEnabled(on: Boolean) {}
    fun setCustomKey(key: String, value: String) {}
    fun setCustomKey(key: String, value: Int) {}
    fun log(msg: String) {}
    fun recordException(t: Throwable) {}
    fun sendUnsentReports() {}
}
