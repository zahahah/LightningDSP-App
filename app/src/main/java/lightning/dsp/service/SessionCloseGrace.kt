package lightning.dsp.service

/** Invalidates delayed close callbacks when the same session is opened again. */
internal class SessionCloseGrace {
    private var nextGeneration = 0L
    private val pending = mutableMapOf<Int, Long>()

    fun schedule(sessionId: Int): Long = (++nextGeneration).also { pending[sessionId] = it }

    fun cancel(sessionId: Int) {
        pending.remove(sessionId)
    }

    fun isCurrent(sessionId: Int, generation: Long): Boolean = pending[sessionId] == generation

    fun complete(sessionId: Int, generation: Long) {
        if (isCurrent(sessionId, generation)) pending.remove(sessionId)
    }

    fun clear() = pending.clear()
}
