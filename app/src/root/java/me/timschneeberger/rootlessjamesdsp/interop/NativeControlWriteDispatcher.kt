package me.timschneeberger.rootlessjamesdsp.interop

/** Serial native writes. Queued means owned by this dispatcher, not applied by
 * the DSP. A failed write falls back only if a newer value has not replaced it. */
internal class NativeControlWriteDispatcher(
    private val isReady: () -> Boolean,
    private val enqueue: (() -> Unit) -> Unit,
    private val execute: (String, List<String>) -> NativeControlReply?,
) {
    private val revisions = mutableMapOf<String, Long>()

    fun dispatch(action: String, args: List<String>, fallback: () -> Unit): Boolean {
        val revision = synchronized(this) {
            ((revisions[action] ?: 0L) + 1L).also { revisions[action] = it }
        }
        if (!isReady()) return false
        enqueue {
            if (isCurrent(action, revision)) {
                val reply = if (isReady()) execute(action, args) else null
                if (reply?.accepted != true && isCurrent(action, revision)) fallback()
            }
        }
        return true
    }

    @Synchronized
    private fun isCurrent(action: String, revision: Long) = revisions[action] == revision
}
