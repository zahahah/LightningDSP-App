package me.timschneeberger.rootlessjamesdsp.interop

/** The optional privileged native-control client is intentionally root-flavor only. */
internal object JamesDspControlClient {
    fun probeAsync(onComplete: ((Boolean) -> Unit)? = null) { onComplete?.invoke(false) }
    fun isAvailable() = false
    fun needsRootPermission() = false
    fun setEnabledIfSupported(enabled: Boolean) = false
    fun setPostGainIfSupported(gainDb: Float, fallback: () -> Unit = {}) = false
    fun setFirEqIfSupported(frequenciesHz: FloatArray, gainsDb: FloatArray,
                           interpolation: Int, enabled: Boolean, fallback: () -> Unit = {}) = false
}
