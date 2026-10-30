package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.PlaybackActivityPolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackActivityPolicyTest {
    private class Player(private val state: Int, private val audible: Boolean) {
        fun getPlayerState() = state
        fun isActive() = audible
    }
    private class LegacyPlayer { fun isActive() = true }

    @Test fun volumeZeroDoesNotHideStartedPlayer() {
        assertTrue(PlaybackActivityPolicy.isPlaying(Player(2, false)))
    }
    @Test fun audibleAndMutedStartedPlayersHaveTheSameVisibility() {
        assertTrue(PlaybackActivityPolicy.isPlaying(Player(2, true)))
        assertTrue(PlaybackActivityPolicy.isPlaying(Player(2, false)))
    }
    @Test fun pausedStoppedReleasedIdleAndUnknownPlayersAreNotPlaying() {
        for (state in listOf(-1, 0, 1, 3, 4)) {
            assertFalse("state=$state", PlaybackActivityPolicy.isPlaying(Player(state, false)))
        }
    }
    @Test fun legacyAccessorIsUsedOnlyWhenStateAccessorIsAbsent() {
        assertTrue(PlaybackActivityPolicy.isPlaying(LegacyPlayer()))
        assertFalse(PlaybackActivityPolicy.isPlaying(Player(3, true)))
    }
}
