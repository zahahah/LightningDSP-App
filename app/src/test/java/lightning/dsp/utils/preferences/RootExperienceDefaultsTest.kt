package lightning.dsp.utils.preferences

import org.junit.Assert.*
import org.junit.Test

class RootExperienceDefaultsTest {
    @Test fun newRootInstallStartsInFusionWithManagerAndControlsEnabled() {
        assertEquals(mapOf("audioformat_processing" to false, "audioformat_processing_app_mixer" to true),
            RootExperienceDefaults.processing(emptyMap<String, Any>()))
        assertEquals(mapOf("enabled" to true), RootExperienceDefaults.manager(emptyMap<String, Any>()))
        assertEquals(mapOf("show_session_dsp_controls" to true, "show_media_controls" to true),
            RootExperienceDefaults.panel(emptyMap<String, Any>()))
    }

    @Test fun upgradesNeverChangeAnyExistingModeIncludingOldSessionWithoutFusionKey() {
        for (global in listOf(false, true)) {
            assertTrue(RootExperienceDefaults.processing(mapOf("audioformat_processing" to global)).isEmpty())
            for (fusion in listOf(false, true)) {
                assertTrue(RootExperienceDefaults.processing(mapOf("audioformat_processing" to global,
                    "audioformat_processing_app_mixer" to fusion)).isEmpty())
            }
        }
        assertTrue(RootExperienceDefaults.processing(mapOf("audioformat_processing_app_mixer" to false)).isEmpty())
    }

    @Test fun explicitDisabledControlsSurviveAndOnlyMissingValuesAreSeeded() {
        assertTrue(RootExperienceDefaults.manager(mapOf("enabled" to false)).isEmpty())
        assertTrue(RootExperienceDefaults.panel(mapOf("show_session_dsp_controls" to false,
            "show_media_controls" to false)).isEmpty())
        assertEquals(mapOf("show_media_controls" to true),
            RootExperienceDefaults.panel(mapOf("show_session_dsp_controls" to false)))
        val seeded = RootExperienceDefaults.panel(emptyMap<String, Any>())
        assertTrue(RootExperienceDefaults.panel(seeded).isEmpty())
    }
}
