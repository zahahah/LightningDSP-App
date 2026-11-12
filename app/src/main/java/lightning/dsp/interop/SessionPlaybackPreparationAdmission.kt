package lightning.dsp.interop

/** Bounds pre-play preparation bookkeeping without serializing distinct sessions from one app. */
internal class SessionPlaybackPreparationAdmission(
    private val maxEntries: Int = 128,
    private val retentionMillis: Long = 10_000,
) {
    private data class Key(val uid: Int, val sessionId: Int)
    private val attempts = linkedMapOf<Key, Long>()

    @Synchronized fun admit(uid: Int, sessionId: Int, now: Long): Boolean {
        attempts.entries.removeAll { now < it.value || now - it.value >= retentionMillis }
        val key = Key(uid, sessionId)
        if (key in attempts) {
            attempts[key] = now
            return true
        }
        if (sessionId <= 0 || attempts.size >= maxEntries) return false
        attempts[key] = now
        return true
    }
}
