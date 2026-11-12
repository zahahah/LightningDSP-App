package lightning.dsp.session.shared

/** Serializes session admission with exclusive ownership and retirement. */
internal class SessionAdmissionGate(private val lock: Any = Any()) {
    private var owner: Any? = null

    fun runIfAllowed(action: () -> Unit): Boolean = synchronized(lock) {
        if (owner != null) false else { action(); true }
    }

    fun runLocked(action: () -> Unit) = synchronized(lock) { action() }

    fun isBlocked(): Boolean = synchronized(lock) { owner != null }

    /** Retirement runs under the same lock as insertion; failures retain the block. */
    fun acquire(retire: () -> Unit): AutoCloseable? = synchronized(lock) {
        if (owner != null) return@synchronized null
        val token = Any()
        owner = token
        retire()
        AutoCloseable { synchronized(lock) { if (owner === token) owner = null } }
    }
}
