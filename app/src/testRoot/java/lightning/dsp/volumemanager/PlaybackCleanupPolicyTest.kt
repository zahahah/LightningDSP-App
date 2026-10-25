package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.PlaybackCleanupPolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackCleanupPolicyTest {
    @Test fun confirmedDeadPlayerNeedsNoRestoration() {
        var attempted = false
        assertTrue(PlaybackCleanupPolicy.restore({ false }) { attempted = true })
        assertFalse(attempted)
    }
    @Test fun livePlayerFailureIsNotCleanupProof() {
        assertFalse(PlaybackCleanupPolicy.restore({ true }) { throw IllegalStateException("remote failure") })
    }
    @Test fun unknownBinderAndReflectionFailureRemainUncertain() {
        assertFalse(PlaybackCleanupPolicy.restore({ null }) { throw NoSuchMethodException("setVolume") })
        assertFalse(PlaybackCleanupPolicy.restore({ throw IllegalStateException("unreadable binder") }) {
            throw IllegalStateException("restore failed")
        })
    }
    @Test fun deathDuringRestorationIsResolvedOnlyAfterConfirmedDeadBinder() {
        var alive = true
        assertTrue(PlaybackCleanupPolicy.restore({ alive }) {
            alive = false
            throw IllegalStateException("owner died during IPC")
        })
    }
    @Test fun successfulRestorationDoesNotRequireInferringBinderDeath() {
        assertTrue(PlaybackCleanupPolicy.restore({ null }) { })
        assertTrue(PlaybackCleanupPolicy.restore({ true }) { })
    }
}
