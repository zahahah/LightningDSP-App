package lightning.dsp.interop

/** Android-independent lifecycle decisions, shared by controller and executable tests. */
internal object SupplementalLifecycle {
    suspend fun <E : Any> releaseIfSafe(
        token: Long,
        original: E?,
        current: () -> E?,
        hasOwned: () -> Boolean,
        awaitBarrier: suspend () -> Boolean,
        generation: () -> Long,
        isClosed: (E) -> Boolean,
        fence: suspend (E, Long) -> Boolean,
        release: (Long) -> Unit,
    ): Boolean {
        if (hasOwned() || !awaitBarrier()) return false
        val expected = generation()
        val observed = current()
        val endpoints = listOfNotNull(original, observed).distinct()
        for (endpoint in endpoints) {
            if (!isClosed(endpoint) && !fence(endpoint, expected)) return false
        }
        // Fences suspend: a preference edit, replacement baseline or newly owned
        // endpoint invalidates this attempt. Keep native blocked and retry later.
        if (generation() != expected || current() !== observed || hasOwned()) return false
        release(token)
        return true
    }

    fun <E> retireOwned(
        ids: Collection<Int>,
        owned: MutableMap<Int, E>,
        hasControl: (E) -> Boolean,
        disable: (E) -> Unit,
        close: (E) -> Unit,
    ) {
        ids.forEach { id ->
            owned.remove(id)?.let { entry ->
                // Never disable an endpoint after another client took control.
                try { if (hasControl(entry)) disable(entry) } finally { close(entry) }
            }
        }
    }
}
