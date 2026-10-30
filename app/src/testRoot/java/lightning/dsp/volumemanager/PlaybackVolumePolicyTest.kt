package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.PlaybackVolumePolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackVolumePolicyTest {
    @Test fun volumeIsAnAttenuationMultiplierAndNeverAnAmplifier() {
        for ((input, expected) in listOf(-2f to 0f, 0f to 0f, 0.38f to 0.38f,
            1f to 1f, 7f to 1f)) {
            assertEquals(expected, PlaybackVolumePolicy.volume(input), 0f)
        }
    }

    @Test fun panPreservesCenterAndBoundsBothChannels() {
        for ((input, expected) in listOf(-4f to -1f, -1f to -1f, -0.4f to -0.4f,
            0f to 0f, 0.6f to 0.6f, 1f to 1f, 4f to 1f)) {
            assertEquals(expected, PlaybackVolumePolicy.pan(input), 0f)
        }
    }

    @Test fun nonFiniteSliderValuesCannotReachPlayerControl() {
        for (input in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { PlaybackVolumePolicy.volume(input) }
            assertThrows(IllegalArgumentException::class.java) { PlaybackVolumePolicy.pan(input) }
            assertThrows(IllegalArgumentException::class.java) {
                PlaybackVolumePolicy.multiplier(enabled = true, muted = false, requested = input)
            }
        }
    }

    @Test fun disablingPerAppControlRestoresUnityEvenForMutedApps() {
        for (muted in listOf(false, true)) {
            for (savedVolume in listOf(0f, 0.25f, 1f)) {
                // Disabling is not mute: Android's player multiplier must return to 0 dB.
                assertEquals(1f, PlaybackVolumePolicy.multiplier(false, muted, savedVolume), 0f)
            }
        }
    }

    @Test fun reEnablingRecoversSavedVolumeAndMuteWithoutChangingThePreference() {
        val savedVolume = 0.42f
        assertEquals(savedVolume, PlaybackVolumePolicy.multiplier(true, false, savedVolume), 0f)
        assertEquals(1f, PlaybackVolumePolicy.multiplier(false, false, savedVolume), 0f)
        assertEquals(savedVolume, PlaybackVolumePolicy.multiplier(true, false, savedVolume), 0f)
        assertEquals(0f, PlaybackVolumePolicy.multiplier(true, true, savedVolume), 0f)
        assertEquals(1f, PlaybackVolumePolicy.multiplier(false, true, savedVolume), 0f)
        assertEquals(0f, PlaybackVolumePolicy.multiplier(true, true, savedVolume), 0f)
    }

    @Test fun onlyAnUnambiguousSinglePackageUidIsWritable() {
        assertTrue(PlaybackVolumePolicy.canControl(1))
        for (count in listOf(-1, 0, 2, 3, Int.MAX_VALUE)) {
            assertFalse("UID package count $count", PlaybackVolumePolicy.canControl(count))
        }
    }
    @Test fun cleanupRestoresUnityAndCenteredPanRatherThanSilencingAudio() {
        val calls = mutableListOf<Pair<String, Float>>()
        PlaybackVolumePolicy.restoreUnity(
            { calls += "volume" to it },
            { calls += "pan" to it }
        )
        assertEquals(listOf("volume" to 1f, "pan" to 0f), calls)
    }

    @Test fun failedVolumeCleanupDoesNotPreventPanRestoration() {
        var restoredPan: Float? = null
        PlaybackVolumePolicy.restoreUnity(
            { throw SecurityException("Player no longer available") },
            { restoredPan = it }
        )
        assertEquals(0f, restoredPan)
    }

    @Test fun unsupportedPanDoesNotUndoSuccessfulVolumeCleanupOrAbortOtherPlayers() {
        val restoredVolumes = mutableListOf<Float>()
        repeat(2) {
            PlaybackVolumePolicy.restoreUnity(
                { restoredVolumes += it },
                { throw UnsupportedOperationException("No firmware pan support") }
            )
        }
        assertEquals(listOf(1f, 1f), restoredVolumes)
    }

}
