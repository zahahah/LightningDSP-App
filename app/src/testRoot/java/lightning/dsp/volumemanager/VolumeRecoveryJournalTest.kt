package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.Files

class VolumeRecoveryJournalTest {
    private val nonce = "a".repeat(64)
    private val owner = VolumeProcessIdentity(101, 0, 100)
    private val server = VolumeProcessIdentity(102, 1000, 90)
    private val client = VolumeProcessIdentity(103, 10123, 110)
    private fun journal() = VolumeRecoveryJournal.header(nonce, "boot-a", owner, server) +
        VolumeRecoveryJournal.client(nonce, client)
    private fun evidence() = VolumeRecoveryJournal.parse(journal(), nonce)!!
    private fun gone() = VolumeProcessObservation.Missing
    private val available = { Closeable {} }

    @Test fun completeJournalIsBoundToNonceAndRejectsMissingTruncatedCorruptAndOversizeEvidence() {
        assertEquals(setOf(client), evidence().clients)
        for (bad in listOf(null, "", journal().trimEnd(), journal().replace("HEADER", "CLIENT"),
            journal().replace(" 10123 ", " invalid "), journal().replace(nonce, "b".repeat(64)),
            journal() + VolumeRecoveryJournal.header(nonce, "boot-a", owner, server),
            journal() + "x".repeat(VolumeRecoveryJournal.LIMIT) + "\n")) {
            assertNull(VolumeRecoveryJournal.parse(bad, nonce))
        }
        assertNotNull(VolumeRecoveryJournal.parse("diagnostic\n" + journal() + "another diagnostic\n", nonce))
    }
    @Test fun sameFrameworkOrSurvivingClientPreventsRecoveryEvenAfterHalRestart() {
        for (survivor in listOf(owner, server, client)) {
            assertFalse(VolumeRecoveryJournal.verify(evidence(), "boot-a", {
                if (it == survivor.pid) VolumeProcessObservation.Present(survivor) else gone()
            }, available))
        }
    }
    @Test fun allOldIdentitiesGoneAndExclusiveOwnershipAllowsRecoveryAndReleasesProbe() {
        var closed = false
        assertTrue(VolumeRecoveryJournal.verify(evidence(), "boot-a", { gone() }, { Closeable { closed = true } }))
        assertTrue(closed)
    }
    @Test fun pidReuseIsDistinguishedByStartTime() {
        assertTrue(VolumeRecoveryJournal.verify(evidence(), "boot-a", { pid ->
            val old = listOf(owner, server, client).single { it.pid == pid }
            VolumeProcessObservation.Present(old.copy(start = old.start + 1))
        }, available))
    }
    @Test fun uidChangeWithoutProcessDeathDoesNotProveCleanup() {
        assertFalse(VolumeRecoveryJournal.verify(evidence(), "boot-a", { pid ->
            val old = listOf(owner, server, client).single { it.pid == pid }
            VolumeProcessObservation.Present(old.copy(uid = old.uid + 1))
        }, available))
    }
    @Test fun unavailableBootUnreadableProcessAndBusyOwnerNeverRecover() {
        assertFalse(VolumeRecoveryJournal.verify(evidence(), null, { gone() }, available))
        assertFalse(VolumeRecoveryJournal.verify(evidence(), "boot-b", { gone() }, available))
        assertFalse(VolumeRecoveryJournal.verify(evidence(), "boot-a", { VolumeProcessObservation.Unknown }, available))
        assertFalse(VolumeRecoveryJournal.verify(evidence(), "boot-a", { gone() }, { error("owner busy") }))
    }
    @Test fun journalFlushAndSyncPrecedeCompletionAndFailedSyncPreventsFurtherMutationRecords() {
        val output = ByteArrayOutputStream()
        var synced = false
        val writer = VolumeRecoveryJournal.Writer(output) {
            assertTrue(output.toString("UTF-8").endsWith("\n")); synced = true
        }
        writer.append(VolumeRecoveryJournal.header(nonce, "boot-a", owner, server))
        assertTrue(synced)
        writer.beforeClient(nonce, client)
        val previousSize = output.size()
        writer.beforeClient(nonce, client)
        assertEquals(previousSize, output.size())
        val failing = VolumeRecoveryJournal.Writer(ByteArrayOutputStream()) { error("fsync failed") }
        assertTrue(runCatching { failing.beforeClient(nonce, client) }.isFailure)
        assertTrue(runCatching { failing.beforeClient(nonce, client.copy(pid = 104)) }.isFailure)
    }
    @Test fun procReaderDoesNotTreatUnreadableOrMalformedExistingEntryAsDead() {
        val directory = Files.createTempDirectory("volume-proc").toFile()
        try {
            val proc = VolumeRecoveryProc(directory)
            assertEquals(VolumeProcessObservation.Missing, proc.observe(103))
            val p = java.io.File(directory, "103").apply { mkdir() }
            assertEquals(VolumeProcessObservation.Unknown, proc.observe(103))
            java.io.File(p, "stat").writeText("103 (name with spaces) S " + "0 ".repeat(18) + "110\n")
            java.io.File(p, "status").writeText("Uid:\t10123\t10123\t10123\t10123\n")
            assertEquals(VolumeProcessObservation.Present(client), proc.observe(103))
            java.io.File(p, "stat").writeText("corrupt")
            assertEquals(VolumeProcessObservation.Unknown, proc.observe(103))
        } finally { directory.deleteRecursively() }
    }
    @Test fun ownerAppendBetweenInitialReadAndSocketAcquisitionInvalidatesProof() {
        var text = journal()
        var writes = 0
        val ledger = VolumeCleanupLedger({ nonce }, { "boot-a" }, { "boot-a" },
            { _, _ -> writes++; true }, { text }, { _, _ ->
                text += VolumeRecoveryJournal.client(nonce, client.copy(pid = 104))
                true
            })
        assertFalse(ledger.recover())
        assertEquals(0, writes)
    }
    @Test fun repeatedSoftRebootRecoveryCommitsBeforeNextActivationAndCannotClearLegacyMarkers() {
        repeat(10) {
            var dirty: String? = nonce
            var committed = false
            val ledger = VolumeCleanupLedger({ dirty }, { "boot-a" }, { "boot-a" },
                { value, _ -> dirty = value; committed = true; true }, { journal() },
                { n, text -> VolumeRecoveryJournal.verify(VolumeRecoveryJournal.parse(text, n)!!,
                    "boot-a", { gone() }, available) })
            assertTrue(ledger.recover()); assertTrue(committed); assertNull(dirty)
        }
        val failedCommit = VolumeCleanupLedger({ nonce }, { "boot-a" }, { "boot-a" },
            { _, _ -> false }, { journal() }, { _, _ -> true })
        assertFalse(failedCommit.recover())
        val legacy = VolumeCleanupLedger({ nonce }, { "boot-a" }, { "boot-a" },
            { _, _ -> error("must not clear") }, { "" }, { _, _ -> error("no journal") })
        assertFalse(legacy.recover())
    }
}
