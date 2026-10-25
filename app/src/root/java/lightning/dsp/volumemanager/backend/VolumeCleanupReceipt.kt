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
    private val persist: (String?) -> Boolean,
    private val readReceipt: (String) -> String?
) {
    fun authorize(nonce: String): Boolean = VolumeCleanupReceipt.validNonce(nonce) && persist(nonce)
    fun confirmLiveCleanup(): Boolean = persist(null)
    fun recover(): Boolean {
        val nonce = dirtyNonce() ?: return true
        if (!VolumeCleanupReceipt.validNonce(nonce)) return false
        return VolumeCleanupReceipt.confirms(readReceipt(nonce), nonce) && persist(null)
    }
}
