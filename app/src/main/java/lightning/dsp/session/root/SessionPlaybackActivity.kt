package lightning.dsp.session.root

import lightning.dsp.model.AudioSessionDumpEntry

/**
 * Bypass processing as soon as playback is explicitly inactive. Retain ownership for a grace
 * period before cleanup, but never report retained idle engines as active playback. Some apps (notably soundbank players and emulators) produce short bursts separated by
 * brief inactive gaps while rotating audio sessions; tearing down immediately makes the next
 * burst start dry while its session effect is recreated.
 */
internal class SessionPlaybackActivity(
    private val graceMillis: Long = INACTIVE_SESSION_GRACE_MILLIS,
) {
    private data class Observation(val uid: Int, val packageName: String, val since: Long, val suspended: Boolean)
    private var inactive = emptyMap<Int, Observation>()
    private var playing = emptyMap<Int, Pair<Int, String>>()
    private var observationAvailable = false
    var needsConfirmation = false
        private set

    fun update(sessions: Map<Int, AudioSessionDumpEntry>, now: Long, retainedSessions: Set<Int> = emptySet()) {
        observationAvailable = true
        val previousPlaying = playing
        playing = sessions.filterValues { it.playbackActive == true }.mapValues { it.value.uid to it.value.packageName }
        val next = inactive.filterKeys { it in sessions || it in retainedSessions }.toMutableMap()
        previousPlaying.filterKeys { it !in sessions && it in retainedSessions }.forEach { (id, identity) ->
            next[id] = Observation(identity.first, identity.second, now, false)
        }
        for ((id, entry) in sessions) {
            if (id <= 0 || entry.playbackActive == true) {
                next.remove(id)
                continue
            }
            if (next[id]?.let { it.uid != entry.uid || it.packageName != entry.packageName } == true)
                next.remove(id)
            if (entry.playbackActive == null) {
                inactive[id]?.takeIf { it.uid == entry.uid && it.packageName == entry.packageName }
                    ?.let { next[id] = it }
                continue
            }
            val previous = inactive[id]?.takeIf { it.uid == entry.uid && it.packageName == entry.packageName && now >= it.since }
            val since = previous?.since ?: now
            next[id] = Observation(entry.uid, entry.packageName, since,
                previous != null && now - since >= graceMillis)
        }
        inactive = next
        needsConfirmation = sessions.any { (id, entry) -> entry.playbackActive == false && next[id]?.suspended == false }
    }

    fun shouldProcess(id: Int, uid: Int, packageName: String): Boolean = inactive[id]
        ?.takeIf { it.uid == uid && it.packageName == packageName } == null

    fun isPlaying(id: Int, uid: Int, packageName: String): Boolean =
        observationAvailable && playing[id] == (uid to packageName)

    fun confirmedInactiveSessions(): Map<Int, Pair<Int, String>> = inactive
        .filterValues { it.suspended }
        .mapValues { (_, observation) -> observation.uid to observation.packageName }

    fun markUnavailable() { observationAvailable = false }

    fun forget(id: Int) { inactive = inactive - id; playing = playing - id; needsConfirmation = inactive.values.any { !it.suspended } }
    fun clear() { inactive = emptyMap(); playing = emptyMap(); observationAvailable = false; needsConfirmation = false }

    companion object {
        /** Keep session effects through short gaps, but still reclaim genuinely stale sessions. */
        const val INACTIVE_SESSION_GRACE_MILLIS = 10_000L
    }
}

/** Notification projection includes active playback and deliberately retained sessions. */
internal object SessionProcessingProjection {
    fun includes(id: Int, processingEnabled: Boolean, playing: Boolean, focusedRetention: Boolean): Boolean =
        id > 0 && processingEnabled && (playing || focusedRetention)
}
