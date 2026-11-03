package lightning.dsp.volumemanager

import android.media.session.PlaybackState
import lightning.dsp.volumemanager.backend.MediaPlaybackControlPolicy
import lightning.dsp.volumemanager.backend.PausedMediaRetention
import lightning.dsp.volumemanager.backend.ExplicitMediaPauseRequests
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackControlPolicyTest {
    @Test fun controlsAppearOnlyForActionsSupportedByTheSession() {
        val playingWithPause = MediaPlaybackControlPolicy.controls(
            PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE
        )
        assertTrue(playingWithPause.visible)
        assertTrue(playingWithPause.playing)

        val pausedWithPlay = MediaPlaybackControlPolicy.controls(
            PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY
        )
        assertTrue(pausedWithPlay.visible)
        assertFalse(pausedWithPlay.playing)

        assertFalse(MediaPlaybackControlPolicy.controls(
            PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PLAY
        ).visible)
        assertFalse(MediaPlaybackControlPolicy.controls(
            PlaybackState.STATE_PAUSED, 0L
        ).visible)
    }

    @Test fun pausedMediaRetentionExpiresAfterThreeMinutesUsingElapsedTime() {
        val retention = PausedMediaRetention()
        retention.retainAfterPauseButton("player", 10_000L)
        assertTrue(retention.contains("player", 189_999L))
        assertFalse(retention.contains("player", 190_000L))
        assertFalse(retention.contains("player", 190_001L))
    }

    @Test fun pauseButtonStartsRetentionImmediatelyAndConfirmationDoesNotRestartTheTimer() {
        val retention = PausedMediaRetention()
        val requests = ExplicitMediaPauseRequests<String>()
        val buttonPressedAt = 10_000L
        requests.request("session", buttonPressedAt)
        retention.retainAfterPauseButton("player", buttonPressedAt)

        assertTrue(requests.isRecent("session", buttonPressedAt + 4_000L))
        assertTrue(retention.contains("player", buttonPressedAt + 179_999L))
        assertFalse(retention.contains("player", buttonPressedAt + 180_000L))
    }

    @Test fun retentionCanBeCancelledWhenPlaybackResumesOrOptionIsDisabled() {
        val retention = PausedMediaRetention()
        retention.retainAfterPauseButton("player", 1L)
        retention.remove("player")
        assertFalse(retention.contains("player", 2L))
        retention.retainAfterPauseButton("player", 2L)
        retention.clear()
        assertFalse(retention.contains("player", 3L))
    }

    @Test fun onlyARecentPauseButtonRequestForTheSameSessionAuthorizesRetention() {
        val requests = ExplicitMediaPauseRequests<String>()
        assertFalse(requests.consumeIfRecent("session", 100L))
        requests.request("session", 100L)
        assertFalse(requests.consumeIfRecent("other-session", 101L))
        assertTrue(requests.consumeIfRecent("session", 5_100L))
        assertFalse(requests.consumeIfRecent("session", 5_101L))
    }

    @Test fun expiredPauseButtonRequestCannotRetainAnUnrelatedNaturalPause() {
        val requests = ExplicitMediaPauseRequests<String>()
        requests.request("session", 100L)
        assertFalse(requests.consumeIfRecent("session", 5_101L))
        assertFalse(requests.consumeIfRecent("session", 5_102L))
    }
}
