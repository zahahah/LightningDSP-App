package lightning.dsp.interop

/** Serial native writes. Queued means owned by this dispatcher, not applied by
 * the DSP. A failed write falls back only if a newer value has not replaced it. */
internal class NativeControlWriteDispatcher(
    private val isReady: () -> Boolean,
    private val enqueue: (() -> Unit) -> Unit,
    private val currentGeneration: () -> Long = { 0L },
    private val execute: (String, List<String>) -> NativeControlReply?,
) {
    private val revisions = mutableMapOf<String, Long>()

    fun dispatch(action: String, args: List<String>, fallback: () -> Unit): Boolean {
        val generation = currentGeneration()
        val revision = synchronized(this) {
            ((revisions[action] ?: 0L) + 1L).also { revisions[action] = it }
        }
        if (!isReady()) return false
        enqueue {
            if (isCurrent(action, revision, generation)) {
                val reply = if (isReady()) execute(action, args) else null
                if (reply?.accepted != true && isCurrent(action, revision, generation)) fallback()
            }
        }
        return true
    }

    private fun isCurrent(action: String, revision: Long, generation: Long) =
        currentGeneration() == generation && synchronized(this) { revisions[action] == revision }
}
