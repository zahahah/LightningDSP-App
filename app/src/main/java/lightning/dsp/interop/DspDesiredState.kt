package lightning.dsp.interop

/** Serializes profile replacement against DSP writes; old queued work is not a new profile. */
internal object DspDesiredState {
    private val lock = Any()
    private var generation = 0L
    fun current(): Long = synchronized(lock) { generation }
    /** Endpoint teardown must exclude writers without invalidating unrelated
     * engines' queued preference work. */
    fun <T> serialized(block: () -> T): T = synchronized(lock) { block() }
    fun <T> replace(block: () -> T): T = synchronized(lock) {
        generation++
        block()
    }
    fun applyIfCurrent(expected: Long, block: () -> Unit) = synchronized(lock) {
        if (generation == expected) block()
    }
}
