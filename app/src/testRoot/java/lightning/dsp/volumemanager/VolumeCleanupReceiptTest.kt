package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumeCleanupLedger
import lightning.dsp.volumemanager.backend.VolumeCleanupReceipt
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class VolumeCleanupReceiptTest {
    private val nonce = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test fun onlyCompleteMatchingPositiveRecordConfirmsCleanup() {
        val positive = VolumeCleanupReceipt.record(nonce, true)
        assertTrue(VolumeCleanupReceipt.confirms(positive, nonce))
        assertTrue(VolumeCleanupReceipt.confirms("su diagnostics\n" + positive + "later log\n", nonce))
        for (invalid in listOf(null, "", positive.trimEnd(), positive.take(30),
            VolumeCleanupReceipt.record(other, true), VolumeCleanupReceipt.record(nonce, false),
            "prefix " + positive, positive + VolumeCleanupReceipt.record(nonce, false))) {
            assertFalse("Invalid receipt accepted: $invalid", VolumeCleanupReceipt.confirms(invalid, nonce))
        }
        assertFalse(VolumeCleanupReceipt.confirms(positive, "../../private-file"))
    }

    @Test fun recordIsFlushedThenSyncedAfterCompleteContent() {
        var synced = false
        val bytes = ByteArrayOutputStream()
        VolumeCleanupReceipt.write(bytes, nonce, true) {
            assertEquals(VolumeCleanupReceipt.record(nonce, true), bytes.toString("UTF-8"))
            synced = true
        }
        assertTrue(synced)
    }

    @Test fun newHostRecoversOnlyMatchingDurableProofAndPersistsCleanState() {
        var persistedNonce: String? = null
        var receipt: String? = null
        fun host() = VolumeCleanupLedger({ persistedNonce }, { persistedNonce = it; true }, { receipt })
        assertTrue(host().authorize(nonce))
        assertEquals(nonce, persistedNonce)
        // A new host gets the same durable dirty marker, not an in-memory reset.
        assertFalse(host().recover())
        assertEquals(nonce, persistedNonce)
        receipt = VolumeCleanupReceipt.record(other, true)
        assertFalse(host().recover())
        receipt = VolumeCleanupReceipt.record(nonce, false)
        assertFalse(host().recover())
        receipt = VolumeCleanupReceipt.record(nonce, true).trimEnd()
        assertFalse(host().recover())
        receipt = VolumeCleanupReceipt.record(nonce, true)
        assertTrue(host().recover())
        assertNull(persistedNonce)
        assertTrue(host().recover())
    }

    @Test fun persistenceFailureNeverGrantsActivationOrRecovery() {
        val denied = VolumeCleanupLedger({ nonce }, { false }, { VolumeCleanupReceipt.record(nonce, true) })
        assertFalse(denied.authorize(nonce))
        assertFalse(denied.recover())
        assertFalse(denied.confirmLiveCleanup())
        var writes = 0
        val invalid = VolumeCleanupLedger({ "../invalid" }, { writes++; true }, { null })
        assertFalse(invalid.authorize("../invalid"))
        assertFalse(invalid.recover())
        assertEquals(0, writes)
    }
}
