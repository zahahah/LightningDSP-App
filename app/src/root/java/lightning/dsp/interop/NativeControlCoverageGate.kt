package lightning.dsp.interop

/** The singleton protocol cannot identify an owned context while extras exist.
 * Membership is changed before invalidation, and the callback is queued behind
 * every earlier native task. Releasing an old owner cannot unblock a newer one.
 */
internal class NativeControlCoverageGate(
    private val enqueue: (() -> Unit) -> Unit,
    private val invalidate: () -> Unit,
) {
    private var nextToken = 1L
    private val owners = mutableSetOf<Long>()
    private var invalidatingReleases = 0

    val blocked: Boolean get() = synchronized(this) { owners.isNotEmpty() || invalidatingReleases > 0 }

    fun acquire(onQuiesced: (Long) -> Unit): Long {
        val token = synchronized(this) {
            check(nextToken < Long.MAX_VALUE) { "Native coverage lease identities exhausted" }
            val next = nextToken++
            owners.add(next)
            next
        }
        // Never call outward while holding the membership lock: DSP writes may
        // already hold the desired-state lock while checking this gate.
        invalidate()
        enqueue {
            if (synchronized(this) { token in owners }) onQuiesced(token)
        }
        return token
    }

    /** True only when this valid release removed the last owner. */
    fun release(token: Long): Boolean {
        synchronized(this) {
            if (!owners.remove(token)) return false
            // Keep readiness blocked until invalidation has completed. Otherwise
            // the last release briefly exposes the previous cached ready state.
            invalidatingReleases++
        }
        try {
            invalidate()
        } finally {
            synchronized(this) { invalidatingReleases-- }
        }
        return synchronized(this) { owners.isEmpty() && invalidatingReleases == 0 }
    }
}
