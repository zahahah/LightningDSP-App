package lightning.dsp.utils

import org.junit.Assert.*
import org.junit.Test

class DeviceProfileTransitionTest {
    @Test fun failedSaveCannotClearPreferencesOrChangeTheActiveProfile() {
        var active = "speaker"
        val values = mutableMapOf("bass" to true, "reverb" to true)
        assertFalse(replaceDeviceProfile(true, true, { false }) {
            values.clear()
            active = "headphones"
        })
        assertEquals("speaker", active)
        assertEquals(mapOf("bass" to true, "reverb" to true), values)
    }

    @Test fun missingActiveMarkerLoadsExistingDestinationWithoutOverwritingIt() {
        var writes = 0
        var loaded = false
        assertTrue(replaceDeviceProfile(false, true, { writes++; true }) { loaded = true })
        assertEquals(0, writes)
        assertTrue(loaded)
    }

    @Test fun firstProfilePreservesCurrentLiveSettingsBeforeLoadingIt() {
        val steps = mutableListOf<String>()
        assertTrue(replaceDeviceProfile(false, false, { steps += "save-live"; true }) { steps += "load" })
        assertEquals(listOf("save-live", "load"), steps)
    }

    @Test fun existingProfileAlwaysSavesBeforeSwitching() {
        val steps = mutableListOf<String>()
        assertTrue(replaceDeviceProfile(true, true, { steps += "save"; true }) { steps += "clear-load" })
        assertEquals(listOf("save", "clear-load"), steps)
    }
}
