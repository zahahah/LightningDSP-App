package lightning.dsp.volumemanager.backend

import java.io.OutputStream

/** Strict complete records only. Diagnostics, stale nonces and partial writes are not proof. */
internal object VolumeCleanupReceipt {
    private const val PREFIX = "LDSP_VOLUME_CLEANUP_V1"
    fun validNonce(nonce: String): Boolean = nonce.matches(Regex("[a-f0-9]{64}"))
    fun record(nonce: String, clean: Boolean): String {
        require(validNonce(nonce))
        return "$PREFIX $nonce ${if (clean) "OK" else "FAILED"}\n"
    }
    fun confirms(text: String?, nonce: String): Boolean {
        if (!validNonce(nonce) || text == null) return false
        val complete = text.substringBeforeLast('\n', "")
        val records = complete.lineSequence().filter { it == "$PREFIX $nonce OK" || it == "$PREFIX $nonce FAILED" }
        return records.lastOrNull() == "$PREFIX $nonce OK"
    }
    fun write(output: OutputStream, nonce: String, clean: Boolean, sync: () -> Unit) {
        output.write(record(nonce, clean).toByteArray(Charsets.UTF_8))
        output.flush()
        sync()
    }
}

/** Storage operations are injected so persistence ordering can be tested without Android. */
internal class VolumeCleanupLedger(
    private val dirtyNonce: () -> String?,
    private val dirtyBootId: () -> String?,
    private val currentBootId: () -> String?,
    private val persist: (String?, String?) -> Boolean,
    private val readReceipt: (String) -> String?
) {
    fun authorize(nonce: String): Boolean = VolumeCleanupReceipt.validNonce(nonce) &&
        persist(nonce, currentBootId()?.takeIf { it.isNotBlank() })
    fun confirmLiveCleanup(): Boolean = persist(null, null)
    fun recover(): Boolean {
        val nonce = dirtyNonce() ?: return true
        if (!VolumeCleanupReceipt.validNonce(nonce)) return false
        val previousBoot = dirtyBootId()
        val currentBoot = currentBootId()
        // A kernel reboot destroys the old process and AudioFlinger playback state.
        if (!previousBoot.isNullOrBlank() && !currentBoot.isNullOrBlank() && previousBoot != currentBoot)
            return persist(null, null)
        if (VolumeCleanupReceipt.confirms(readReceipt(nonce), nonce)) return persist(null, null)
        // Old app versions did not persist boot identity. Baseline them conservatively:
        // never release in this boot, but permit recovery after a subsequent reboot.
        if (previousBoot.isNullOrBlank() && !currentBoot.isNullOrBlank()) persist(nonce, currentBoot)
        return false
    }
}
