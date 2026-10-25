package lightning.dsp.volumemanager.backend

/** Clock-injected private-pipe liveness, independent of changing playback snapshots. */
internal class VolumeHostLease(startedAt: Long, private val timeoutMillis: Long = 30_000) {
    @Volatile private var lastCommandAt = startedAt
    fun renew(now: Long) { lastCommandAt = now }
    fun expired(now: Long): Boolean = now - lastCommandAt > timeoutMillis
}
