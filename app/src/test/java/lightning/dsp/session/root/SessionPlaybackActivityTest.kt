package lightning.dsp.session.root

import lightning.dsp.model.AudioSessionDumpEntry
import org.junit.Assert.*
import org.junit.Test

class SessionPlaybackActivityTest {
    private fun entry(active: Boolean?, uid: Int = 10322, name: String = "player") =
        AudioSessionDumpEntry(uid, name, "AUDIO_USAGE_MEDIA", "AUDIO_CONTENT_TYPE_MUSIC", active)

    @Test fun confirmedInactiveSessionIsEligibleForEffectCleanup() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(4961 to entry(false)), 0)
        assertTrue(policy.needsConfirmation)
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
        // Merely waiting does not apply an old observation.
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
        policy.update(mapOf(4961 to entry(false)), 999)
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
        policy.update(mapOf(4961 to entry(false)), 1_000)
        assertFalse(policy.shouldProcess(4961, 10322, "player"))
        assertFalse(policy.needsConfirmation)
        assertEquals(mapOf(4961 to (10322 to "player")), policy.confirmedInactiveSessions())
        policy.update(mapOf(4961 to entry(true)), 1_001)
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
        assertTrue(policy.confirmedInactiveSessions().isEmpty())
    }

    @Test fun activeSessionKeepsProcessingWhilePausedSessionsAreSuspended() {
        val policy = SessionPlaybackActivity()
        val sessions = mapOf(
            4961 to entry(true, uid = 10322, name = "spotify"),
            2321 to entry(false, uid = 10548, name = "paused-a"),
            2329 to entry(false, uid = 10548, name = "paused-b"),
            2337 to entry(false, uid = 10548, name = "paused-c"),
        )

        policy.update(sessions, 10_000)
        assertTrue(policy.shouldProcess(4961, 10322, "spotify"))
        assertTrue(policy.shouldProcess(2321, 10548, "paused-a"))
        policy.update(sessions, 11_000)

        assertTrue(policy.shouldProcess(4961, 10322, "spotify"))
        assertFalse(policy.shouldProcess(2321, 10548, "paused-a"))
        assertFalse(policy.shouldProcess(2329, 10548, "paused-b"))
        assertFalse(policy.shouldProcess(2337, 10548, "paused-c"))
        assertFalse(policy.needsConfirmation)
    }

    @Test fun activeUnknownAndMissingObservationsImmediatelyRestoreProcessing() {
        for (next in listOf(mapOf(4961 to entry(true)), mapOf(4961 to entry(null)), emptyMap())) {
            val policy = SessionPlaybackActivity()
            policy.update(mapOf(4961 to entry(false)), 0)
            policy.update(mapOf(4961 to entry(false)), 1_000)
            assertFalse(policy.shouldProcess(4961, 10322, "player"))
            policy.update(next, 1_001)
            assertTrue(policy.shouldProcess(4961, 10322, "player"))
            assertFalse(policy.needsConfirmation)
        }
    }

    @Test fun reusedUidOrPackageDoesNotInheritSuspensionOrGrace() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(4961 to entry(false)), 0)
        policy.update(mapOf(4961 to entry(false)), 1_000)
        assertTrue(policy.shouldProcess(4961, 99999, "player"))
        assertTrue(policy.shouldProcess(4961, 10322, "different"))
        policy.update(mapOf(4961 to entry(false, name = "different")), 1_001)
        assertTrue(policy.shouldProcess(4961, 10322, "different"))
        assertTrue(policy.needsConfirmation)
    }

    @Test fun globalSessionIsNeverSuspendedAndClearCancelsPendingEvidence() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(0 to entry(false), 4961 to entry(false)), 0)
        policy.update(mapOf(0 to entry(false), 4961 to entry(false)), 1_000)
        assertTrue(policy.shouldProcess(0, 10322, "player"))
        policy.clear()
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
        assertFalse(policy.needsConfirmation)
        policy.update(mapOf(4961 to entry(false)), 2_000)
        policy.forget(4961)
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
        assertFalse(policy.needsConfirmation)
    }

    @Test fun rollbackAndInterruptedInactivityRestartTheGrace() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(4961 to entry(false)), 10_000)
        policy.update(mapOf(4961 to entry(false)), 0)
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
        policy.update(mapOf(4961 to entry(true)), 500)
        policy.update(mapOf(4961 to entry(false)), 1_000)
        assertTrue(policy.shouldProcess(4961, 10322, "player"))
    }
}
