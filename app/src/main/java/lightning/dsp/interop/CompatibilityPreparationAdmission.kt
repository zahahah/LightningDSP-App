package lightning.dsp.interop

/** Bound unsolicited IPC work per caller as well as total identity bookkeeping. */
internal class CompatibilityPreparationAdmission {
    companion object {
        fun current(uid: Int, requestUid: Int, deadline: Long, now: Long): Boolean =
            uid == requestUid && now < deadline
    }
    private val last = mutableMapOf<Int, Long>()
    /** Successful serial stream opens must not be throttled like unsolicited failed probes. */
    @Synchronized fun succeeded(uid: Int, admittedAt: Long) {
        if (last[uid] == admittedAt) last.remove(uid)
    }
    @Synchronized fun admit(uid: Int, now: Long): Boolean {
        last.entries.removeAll { now < it.value || now - it.value >= 60_000 }
        val previous = last[uid]
        if (previous != null && now - previous < 1_000) return false
        if (previous == null && last.size >= 64) return false
        last[uid] = now
        return true
    }
}
