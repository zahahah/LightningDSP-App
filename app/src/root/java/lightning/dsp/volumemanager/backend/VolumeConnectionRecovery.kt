package lightning.dsp.volumemanager.backend

/** Worker-confined retry budget. A briefly connected child must not create a restart loop. */
internal class VolumeConnectionRecovery(private val healthyMillis: Long = 60_000) {
    private var attempts = 0
    private var healthySince: Long? = null

    fun reset() {
        attempts = 0
        healthySince = null
    }

    fun healthyResponse(now: Long) {
        val since = healthySince
        if (since == null || now < since) healthySince = now
        else if (now - since >= healthyMillis) attempts = 0
    }

    fun disconnected() { healthySince = null }

    fun nextDelayMillis(enabled: Boolean, hasOwner: Boolean, cleanupVerified: Boolean): Long? {
        if (!enabled || !hasOwner || !cleanupVerified || attempts >= DELAYS.size) return null
        return DELAYS[attempts++]
    }

    private companion object { val DELAYS = longArrayOf(1_000, 3_000, 10_000) }
}
