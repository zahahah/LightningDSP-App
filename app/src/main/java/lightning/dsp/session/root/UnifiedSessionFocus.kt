package lightning.dsp.session.root

/** Focus is a short-lived hint from a verified owner, never proof that an audio session exists. */
internal class UnifiedSessionFocus(private val limit: Int = 32, private val now: () -> Long) {
    private data class Owner(val packageName: String, val refreshedAt: Long, val alive: () -> Boolean)
    private val owners = linkedMapOf<Pair<Int, Int>, Owner>()
    private var applied = emptySet<Pair<Int, String>>()

    @Synchronized fun update(uid: Int, pid: Int, packageName: String, focused: Boolean, alive: () -> Boolean): Boolean {
        prune()
        if (uid % 100000 !in 10000..19999 || pid <= 0 || packageName.isBlank()) return false
        val key = uid to pid
        if (!focused) { owners.remove(key); return true }
        if (!runCatching(alive).getOrDefault(false) || (key !in owners && owners.size >= limit)) return false
        val timestamp = now()
        if (timestamp < 0) return false
        owners[key] = Owner(packageName, timestamp, alive)
        return true
    }

    @Synchronized fun isFocused(uid: Int, packageName: String): Boolean = owners.any { (key, owner) ->
        key.first == uid && owner.packageName == packageName && current(owner, now())
    }

    @Synchronized fun reconcile(enabled: Boolean): Boolean {
        if (!enabled) owners.clear() else prune()
        val next = owners.map { (key, owner) -> key.first to owner.packageName }.toSet()
        return (next != applied).also { applied = next }
    }
    @Synchronized fun hasOwners() = owners.isNotEmpty()
    @Synchronized fun clear() { owners.clear(); applied = emptySet() }
    private fun current(owner: Owner, timestamp: Long): Boolean =
        timestamp >= owner.refreshedAt && timestamp - owner.refreshedAt < FOCUS_TTL_MS &&
            runCatching(owner.alive).getOrDefault(false)
    private fun prune() {
        val timestamp = now()
        owners.entries.removeAll { !current(it.value, timestamp) }
    }

    companion object { const val FOCUS_TTL_MS = 6_000L }
}
