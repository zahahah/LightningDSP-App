package lightning.dsp.session.root

import lightning.dsp.model.AudioSessionDumpEntry

/** Pause processing only after two fresh, identity-consistent inactive observations. */
internal class SessionPlaybackActivity(private val graceMillis: Long = 1_000) {
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

    fun forget(id: Int) { inactive = inactive - id; needsConfirmation = inactive.values.any { !it.suspended } }
    fun clear() { inactive = emptyMap(); needsConfirmation = false }
}
