package lightning.dsp.volumemanager

import android.media.session.PlaybackState
import lightning.dsp.volumemanager.backend.MediaPlaybackControlPolicy
import lightning.dsp.volumemanager.backend.PausedMediaRetention
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
        retention.retain("player", 10_000L)
        assertTrue(retention.contains("player", 189_999L))
        assertFalse(retention.contains("player", 190_000L))
        assertFalse(retention.contains("player", 190_001L))
    }

    @Test fun retentionCanBeCancelledWhenPlaybackResumesOrOptionIsDisabled() {
        val retention = PausedMediaRetention()
        retention.retain("player", 1L)
        retention.remove("player")
        assertFalse(retention.contains("player", 2L))
        retention.retain("player", 2L)
        retention.clear()
        assertFalse(retention.contains("player", 3L))
    }
}
