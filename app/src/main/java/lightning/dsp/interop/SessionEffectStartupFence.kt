package lightning.dsp.interop

/** Keeps a newly attached session effect bypassed until its complete DSP state is written. */
internal class SessionEffectStartupFence(
    private val canUseEndpoint: () -> Boolean,
    private val isEnabled: () -> Boolean,
    private val writeCompleteState: suspend (Long) -> Boolean,
    private val setEnabled: (Boolean) -> Unit,
) {
    @Volatile var ready = false
        private set

    @Volatile private var requestedEnabled = false

    fun requestEnabled(value: Boolean) {
        requestedEnabled = value
        if (ready || !value) setEnabled(value)
    }

    /** A recreated AudioEffect endpoint must receive a full state before reuse. */
    fun reset() {
        ready = false
        setEnabled(false)
    }

    /** Applies the latest session eligibility only after all DSP namespaces succeed. */
    suspend fun prepare(expectedGeneration: Long, shouldEnable: () -> Boolean): Boolean {
        if (!canUseEndpoint() || isEnabled() || DspDesiredState.current() != expectedGeneration) return false
        val prepared = writeCompleteState(expectedGeneration) && canUseEndpoint() &&
            DspDesiredState.current() == expectedGeneration
        if (!prepared) return false

        var activated = false
        DspDesiredState.applyIfCurrent(expectedGeneration) {
            if (!canUseEndpoint()) return@applyIfCurrent
            ready = true
            requestedEnabled = shouldEnable()
            setEnabled(requestedEnabled)
            activated = ready && canUseEndpoint() && DspDesiredState.current() == expectedGeneration
        }
        return activated
    }

    fun invalidate() {
        ready = false
        requestedEnabled = false
        setEnabled(false)
    }
}
