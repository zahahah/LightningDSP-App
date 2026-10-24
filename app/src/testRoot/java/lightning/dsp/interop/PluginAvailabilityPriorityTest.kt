package lightning.dsp.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginAvailabilityPriorityTest {
    @Test
    fun readyGlobalControlSkipsLegacyAvailabilityCheck() {
        var legacyCheckCalled = false

        val result = LightningDspRemoteEngine.resolvePluginAvailability(nativeControlReady = true) {
            legacyCheckCalled = true
            LightningDspRemoteEngine.PluginState.Unavailable
        }

        assertEquals(LightningDspRemoteEngine.PluginState.Available, result)
        assertFalse(legacyCheckCalled)
    }

    @Test
    fun unavailableGlobalControlFallsBackToLegacyAvailabilityCheck() {
        val result = LightningDspRemoteEngine.resolvePluginAvailability(nativeControlReady = false) {
            LightningDspRemoteEngine.PluginState.Unavailable
        }

        assertEquals(LightningDspRemoteEngine.PluginState.Unavailable, result)
    }
}
