package lightning.dsp.session.root

import lightning.dsp.model.AudioSessionDumpEntry
import org.junit.Assert.*
import org.junit.Test

class SessionPlaybackActivityTest {
    private fun entry(active: Boolean?, uid: Int = 10322, name: String = "player") =
        AudioSessionDumpEntry(uid, name, "AUDIO_USAGE_MEDIA", "AUDIO_CONTENT_TYPE_MUSIC", active)

    @Test fun idleEngineIsBypassedImmediatelyButRetainedOnlyForBoundedReuse() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(42 to entry(false)), 0)
        assertFalse(policy.shouldProcess(42, 10322, "player"))
        assertFalse(policy.isPlaying(42, 10322, "player"))
        assertTrue(policy.confirmedInactiveSessions().isEmpty())
        assertTrue(policy.needsConfirmation)
        policy.update(mapOf(42 to entry(false)), 10_000)
        assertEquals(mapOf(42 to (10322 to "player")), policy.confirmedInactiveSessions())
        assertFalse(policy.needsConfirmation)
    }

    @Test fun freshPlaybackRestoresRetainedSessionWithoutInheritingIdleState() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(42 to entry(false)), 0)
        policy.update(mapOf(42 to entry(true)), 500)
        assertTrue(policy.shouldProcess(42, 10322, "player"))
        assertTrue(policy.isPlaying(42, 10322, "player"))
        assertTrue(policy.confirmedInactiveSessions().isEmpty())
    }

    @Test fun unknownObservationCannotReenableKnownIdleOrAdvertisePlayback() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(42 to entry(false)), 0)
        policy.update(mapOf(42 to entry(null)), 500)
        assertFalse(policy.shouldProcess(42, 10322, "player"))
        assertFalse(policy.isPlaying(42, 10322, "player"))
        assertTrue(policy.shouldProcess(42, 99999, "other"))
        assertFalse(policy.isPlaying(42, 99999, "other"))
    }

    @Test fun ownershipAndUnknownActivityNeverCountAsActivePlayback() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(42 to entry(true), 43 to entry(false), 44 to entry(null)), 0)
        assertTrue(policy.isPlaying(42, 10322, "player"))
        assertFalse(policy.isPlaying(43, 10322, "player"))
        assertFalse(policy.isPlaying(44, 10322, "player"))
        assertFalse(policy.isPlaying(45, 10322, "player"))
        assertFalse(policy.shouldProcess(43, 10322, "player"))
        assertTrue(policy.shouldProcess(42, 10322, "player"))
    }

    @Test fun missingRetainedSessionIsBypassedUntilFreshPlaybackAndForgetDropsProjection() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(42 to entry(true)), 0)
        policy.update(emptyMap(), 100, setOf(42))
        assertFalse(policy.isPlaying(42, 10322, "player"))
        assertFalse(policy.shouldProcess(42, 10322, "player"))
        policy.update(emptyMap(), 200, setOf(42))
        assertFalse(policy.shouldProcess(42, 10322, "player"))
        policy.update(mapOf(42 to entry(true)), 300, setOf(42))
        assertTrue(policy.isPlaying(42, 10322, "player"))
        assertTrue(policy.shouldProcess(42, 10322, "player"))
        policy.forget(42)
        assertFalse(policy.isPlaying(42, 10322, "player"))
    }

    @Test fun missingPlayingSessionIsBypassedAndRetainedOnlyUntilConfirmedAbsent() {
        val policy = SessionPlaybackActivity(graceMillis = 1_000)
        policy.update(mapOf(42 to entry(true)), 0)
        policy.update(emptyMap(), 100, setOf(42))
        assertFalse(policy.shouldProcess(42, 10322, "player"))
        assertTrue(policy.awaitingMissingConfirmation(42, 10322, "player"))
        assertFalse(policy.awaitingMissingConfirmation(42, 10323, "player"))
        assertTrue(policy.needsConfirmation)
        policy.update(emptyMap(), 1_100, setOf(42))
        assertFalse(policy.awaitingMissingConfirmation(42, 10322, "player"))
        assertFalse(policy.needsConfirmation)
        assertEquals(mapOf(42 to (10322 to "player")), policy.confirmedInactiveSessions())
    }

    @Test fun failureHidesUnverifiedPlaybackWithoutInventingInactivityOrErasingEvidence() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(42 to entry(true)), 0)
        policy.markUnavailable()
        assertFalse(policy.isPlaying(42, 10322, "player"))
        assertTrue(policy.shouldProcess(42, 10322, "player"))
        policy.update(emptyMap(), 100, setOf(42))
        assertFalse(policy.shouldProcess(42, 10322, "player"))
        policy.markUnavailable()
        assertFalse(policy.shouldProcess(42, 10322, "player"))
    }

    @Test fun changedIdentityDoesNotInheritIdleEvidenceAndGlobalIsExempt() {
        val policy = SessionPlaybackActivity()
        policy.update(mapOf(0 to entry(false), 42 to entry(false)), 0)
        assertTrue(policy.shouldProcess(0, 10322, "player"))
        policy.update(mapOf(42 to entry(null, name = "other")), 1)
        assertTrue(policy.shouldProcess(42, 10322, "other"))
        assertFalse(policy.isPlaying(42, 10322, "other"))
        policy.clear()
        assertFalse(policy.needsConfirmation)
    }
}
