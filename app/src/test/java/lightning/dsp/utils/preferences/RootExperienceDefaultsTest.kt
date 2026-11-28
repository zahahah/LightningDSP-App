package lightning.dsp.utils.preferences

import org.junit.Assert.*
import org.junit.Test

class RootExperienceDefaultsTest {
    @Test fun newRootInstallDefaultsToGlobalOnAndroid14And15AndFusionElsewhere() {
        val global = mapOf("audioformat_processing" to true, "audioformat_processing_app_mixer" to false)
        val fusion = mapOf("audioformat_processing" to false, "audioformat_processing_app_mixer" to true)
        assertEquals(global, RootExperienceDefaults.processing(emptyMap<String, Any>(), 34))
        assertEquals(global, RootExperienceDefaults.processing(emptyMap<String, Any>(), 35))
        for (sdkInt in listOf(33, 36, 37)) {
            assertEquals(fusion, RootExperienceDefaults.processing(emptyMap<String, Any>(), sdkInt))
        }
        assertEquals(mapOf("enabled" to true), RootExperienceDefaults.manager(emptyMap<String, Any>()))
        assertEquals(mapOf("show_session_dsp_controls" to true, "show_media_controls" to true),
            RootExperienceDefaults.panel(emptyMap<String, Any>()))
    }

    @Test fun upgradesNeverChangeAnyExistingModeIncludingOldSessionWithoutFusionKey() {
        for (global in listOf(false, true)) {
            for (sdkInt in listOf(34, 35, 36)) {
                assertTrue(RootExperienceDefaults.processing(mapOf("audioformat_processing" to global), sdkInt).isEmpty())
            }
            for (fusion in listOf(false, true)) {
                for (sdkInt in listOf(34, 35, 36)) {
                    assertTrue(RootExperienceDefaults.processing(mapOf("audioformat_processing" to global,
                        "audioformat_processing_app_mixer" to fusion), sdkInt).isEmpty())
                }
            }
        }
        for (sdkInt in listOf(34, 35, 36)) {
            assertTrue(RootExperienceDefaults.processing(mapOf("audioformat_processing_app_mixer" to false), sdkInt).isEmpty())
        }
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
