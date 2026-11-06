package lightning.dsp.session.root

import lightning.dsp.model.AudioSessionDumpEntry

/**
 * Pause processing only after two fresh, identity-consistent inactive observations and a grace
 * period. Some apps (notably soundbank players and emulators) produce short bursts separated by
 * brief inactive gaps while rotating audio sessions; tearing down immediately makes the next
 * burst start dry while its session effect is recreated.
 */
internal class SessionPlaybackActivity(
    private val graceMillis: Long = INACTIVE_SESSION_GRACE_MILLIS,
) {
    private data class Observation(val uid: Int, val packageName: String, val since: Long, val suspended: Boolean)
    private var inactive = emptyMap<Int, Observation>()
    var needsConfirmation = false
        private set

    fun update(sessions: Map<Int, AudioSessionDumpEntry>, now: Long) {
        val next = mutableMapOf<Int, Observation>()
        for ((id, entry) in sessions) {
            if (id <= 0 || entry.playbackActive != false) continue
            val previous = inactive[id]?.takeIf { it.uid == entry.uid && it.packageName == entry.packageName && now >= it.since }
            val since = previous?.since ?: now
            next[id] = Observation(entry.uid, entry.packageName, since,
                previous != null && now - since >= graceMillis)
        }
        inactive = next
        needsConfirmation = next.values.any { !it.suspended }
    }

    fun shouldProcess(id: Int, uid: Int, packageName: String): Boolean = inactive[id]
        ?.takeIf { it.uid == uid && it.packageName == packageName }?.suspended != true

    fun confirmedInactiveSessions(): Map<Int, Pair<Int, String>> = inactive
        .filterValues { it.suspended }
        .mapValues { (_, observation) -> observation.uid to observation.packageName }

    fun forget(id: Int) { inactive = inactive - id; needsConfirmation = inactive.values.any { !it.suspended } }
    fun clear() { inactive = emptyMap(); needsConfirmation = false }

    companion object {
        /** Keep session effects through short gaps, but still reclaim genuinely stale sessions. */
        const val INACTIVE_SESSION_GRACE_MILLIS = 10_000L
    }
}
