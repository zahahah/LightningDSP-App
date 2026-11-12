package lightning.dsp.session.root

import lightning.dsp.model.AudioSessionDumpEntry
import lightning.dsp.session.dump.data.SessionOwnerRef

/** Process-scoped opt-in hints never substitute for a current framework player record. */
internal class UnifiedSessionRetention(private val limit: Int = 32) {
    private data class Claim(val uid: Int, val pid: Int, val alive: () -> Boolean)
    private val claims = linkedMapOf<Int, Claim>()
    private var verified = emptyMap<Int, Pair<Int, String>>()
    private var reconciliationPending = false

    @Synchronized fun claim(id: Int, uid: Int, pid: Int, alive: () -> Boolean): Boolean {
        pruneDead()
        if (id <= 0 || uid % 100000 !in 10000..19999 || pid <= 0 || !runCatching(alive).getOrDefault(false)) return false
        val previous = claims[id]
        if (previous != null && (previous.uid != uid || previous.pid != pid)) return false
        // A failed native reused-ID open can allocate a replacement pin. Withdraw the
        // old idle exemption before accepting the new one for this same live process.
        val replaced = claims.filter { (sid, owner) -> sid != id && owner.uid == uid && owner.pid == pid }.keys
        replaced.forEach { claims.remove(it) }
        if (verified.keys.any { it in replaced }) reconciliationPending = true
        verified = verified - replaced
        if (previous == null && claims.size >= limit) return false
        claims[id] = Claim(uid, pid, alive)
        return true
    }

    @Synchronized fun update(sessions: Map<Int, AudioSessionDumpEntry>, enabled: Boolean,
                             ownerRefs: Set<SessionOwnerRef>? = null) {
        if (!enabled) { clear(); return }
        pruneDead()
        verified = claims.mapNotNull { (id, owner) ->
            val entry = sessions[id]
            if (entry != null) {
                entry.takeIf { it.uid == owner.uid && it.isUsageRecordable() }
                    ?.let { id to (it.uid to it.packageName) }
            } else {
                verified[id]?.takeIf { previous -> previous.first == owner.uid &&
                    ownerRefs?.contains(SessionOwnerRef(id, owner.pid, owner.uid)) == true }
                    ?.let { id to it }
            }
        }.toMap()
    }

    @Synchronized fun shouldRetain(id: Int, uid: Int, packageName: String): Boolean =
        verified[id] == (uid to packageName) && claims[id]?.let { runCatching(it.alive).getOrDefault(false) } == true

    /** Withdraw expired/dead/disabled exemptions even when no new dump arrives. */
    @Synchronized fun reconcile(enabled: Boolean): Boolean {
        val previous = verified
        if (!enabled) claims.clear() else pruneDead()
        verified = verified.filterKeys { it in claims }
        return (reconciliationPending || previous != verified).also { reconciliationPending = false }
    }
    @Synchronized fun hasClaims(): Boolean = claims.isNotEmpty()
    @Synchronized fun markUnavailable() {
        reconciliationPending = reconciliationPending || verified.isNotEmpty()
        verified = emptyMap()
    }
    @Synchronized fun forget(id: Int) { claims.remove(id); verified = verified - id }
    @Synchronized fun clear() { claims.clear(); verified = emptyMap(); reconciliationPending = false }
    private fun pruneDead() { claims.entries.removeAll { !runCatching(it.value.alive).getOrDefault(false) } }
}
