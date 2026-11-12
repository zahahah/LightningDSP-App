package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.RemoteVolumeEventPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVolumeEventPolicyTest {
    @Test fun observationIsScopedToSamsung() {
        assertTrue(RemoteVolumeEventPolicy.shouldObserve("Samsung"))
        assertTrue(RemoteVolumeEventPolicy.shouldObserve("samsung"))
        assertFalse(RemoteVolumeEventPolicy.shouldObserve("Google"))
        assertFalse(RemoteVolumeEventPolicy.shouldObserve(null))
    }
    @Test fun onlyExplicitUiRequestsOutsideBypassOpenThePanel() {
        assertTrue(RemoteVolumeEventPolicy.shouldShow(1, false))
        assertTrue(RemoteVolumeEventPolicy.shouldShow(0x101, false))
        assertFalse(RemoteVolumeEventPolicy.shouldShow(0, false))
        assertFalse(RemoteVolumeEventPolicy.shouldShow(0x100, false))
        assertFalse(RemoteVolumeEventPolicy.shouldShow(1, true))
    }
}
