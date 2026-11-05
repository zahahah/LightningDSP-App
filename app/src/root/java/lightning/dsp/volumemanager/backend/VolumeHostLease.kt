package lightning.dsp.volumemanager.backend

/** Awake-time private-pipe liveness; callers supply uptime, which excludes deep sleep. */
internal class VolumeHostLease(startedAt: Long, private val timeoutMillis: Long = 30_000) {
    @Volatile private var lastCommandAt = startedAt
    fun renew(now: Long) { lastCommandAt = now }
    fun expired(now: Long): Boolean = now - lastCommandAt > timeoutMillis
}
