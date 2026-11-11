package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.AppMixerModeSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMixerModeSelectionTest {
    @Test
    fun appsAreIncludedByDefault() {
        assertTrue(AppMixerModeSelection.isIncluded("music.player", emptySet()))
    }

    @Test
    fun excludeThenReincludeOnlyChangesSelectedPackage() {
        val excluded = AppMixerModeSelection.withIncluded("music.player", false, emptySet())
        assertEquals(setOf("music.player"), excluded)
        assertFalse(AppMixerModeSelection.isIncluded("music.player", excluded))

        val withOtherExclusion = AppMixerModeSelection.withIncluded("video.player", false, excluded)
        val reIncluded = AppMixerModeSelection.withIncluded("music.player", true, withOtherExclusion)
        assertEquals(setOf("video.player"), reIncluded)
        assertTrue(AppMixerModeSelection.isIncluded("music.player", reIncluded))
        assertFalse(AppMixerModeSelection.isIncluded("video.player", reIncluded))
    }
}
