package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginAvailabilityPriorityTest {
    @Test
    fun readyGlobalControlSkipsLegacyAvailabilityCheck() {
        var legacyCheckCalled = false

        val result = JamesDspRemoteEngine.resolvePluginAvailability(nativeControlReady = true) {
            legacyCheckCalled = true
            JamesDspRemoteEngine.PluginState.Unavailable
        }

        assertEquals(JamesDspRemoteEngine.PluginState.Available, result)
        assertFalse(legacyCheckCalled)
    }

    @Test
    fun unavailableGlobalControlFallsBackToLegacyAvailabilityCheck() {
        val result = JamesDspRemoteEngine.resolvePluginAvailability(nativeControlReady = false) {
            JamesDspRemoteEngine.PluginState.Unavailable
        }

        assertEquals(JamesDspRemoteEngine.PluginState.Unavailable, result)
    }
}
