package lightning.dsp.volumemanager.backend

/** Content updates and liveness travel separately so idle players do not churn the UI. */
internal class PlaybackSnapshotGate(private val heartbeatMillis: Long = 5_000) {
    enum class Update { SNAPSHOT, HEARTBEAT, NONE }

    private var previous: String? = null
    private var lastSent = 0L

    fun next(signature: String, nowMillis: Long): Update {
        val update = when {
            signature != previous -> Update.SNAPSHOT
            nowMillis < lastSent || nowMillis - lastSent >= heartbeatMillis -> Update.HEARTBEAT
            else -> Update.NONE
        }
        if (update != Update.NONE) {
            previous = signature
            lastSent = nowMillis
        }
        return update
    }
}
