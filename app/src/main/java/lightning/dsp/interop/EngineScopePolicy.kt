package lightning.dsp.interop

/** Protocol 3 has no per-session addressing; a positive session must never use
 * the global singleton even if an earlier probe cached ready=true. */
internal object EngineScopePolicy {
    fun usesUnscopedNative(sessionId: Int, supplemental: Boolean) = sessionId == 0 && !supplemental

    fun publishSampleRate(enabled: Boolean, value: Float, publish: (Float) -> Unit) {
        if (enabled) publish(value)
    }
}

/** Admission and activation share the actual full-state writer result. Keeping
 * this endpoint-independent lets host tests execute the same lifecycle that the
 * AudioEffect engine uses, including changes while the suspend writer runs. */
internal class SupplementalStateFence(
    private val canUseEndpoint: () -> Boolean,
    private val isEnabled: () -> Boolean,
    private val writeCompleteState: suspend (Long) -> Boolean,
    private val enable: () -> Unit,
) {
    @Volatile var admitted = false
        private set
    @Volatile var preparedGeneration = -1L
        private set

    suspend fun prepare(expectedGeneration: Long): Boolean {
        if (!canUseEndpoint() || isEnabled() || DspDesiredState.current() != expectedGeneration) return false
        admitted = true
        var prepared = false
        try {
            prepared = writeCompleteState(expectedGeneration) && canUseEndpoint() &&
                DspDesiredState.current() == expectedGeneration
            return prepared
        } finally {
            preparedGeneration = if (prepared) expectedGeneration else -1L
            if (!prepared) admitted = false
        }
    }

    fun activate(expectedGeneration: Long): Boolean {
        var activated = false
        DspDesiredState.applyIfCurrent(expectedGeneration) {
            if (admitted && preparedGeneration == expectedGeneration && canUseEndpoint()) {
                enable()
                activated = isEnabled() && canUseEndpoint()
            }
        }
        return activated
    }

    fun invalidate() {
        admitted = false
        preparedGeneration = -1L
    }
}
