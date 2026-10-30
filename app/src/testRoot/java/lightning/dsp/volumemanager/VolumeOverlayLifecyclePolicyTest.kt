package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumeOverlayLifecyclePolicy
import lightning.dsp.volumemanager.ui.VolumeOverlayLifecyclePolicy.StateAction
import org.junit.Assert.*
import org.junit.Test

class VolumeOverlayLifecyclePolicyTest {
    @Test fun `preflight ignores synchronously replayed disabled state after master enabled`() {
        assertEquals(StateAction.IGNORE, VolumeOverlayLifecyclePolicy.stateAction(true, true, false))
        assertEquals(StateAction.RENDER, VolumeOverlayLifecyclePolicy.stateAction(true, true, true))
    }

    @Test fun `authoritative disable closes current host even if replay says enabled`() {
        for (observed in listOf(false, true))
            assertEquals(StateAction.HIDE, VolumeOverlayLifecyclePolicy.stateAction(true, false, observed))
    }

    @Test fun `obsolete host callbacks cannot render or close replacement host`() {
        for (master in listOf(false, true)) for (observed in listOf(false, true))
            assertEquals(StateAction.IGNORE, VolumeOverlayLifecyclePolicy.stateAction(false, master, observed))
    }

    @Test fun `dismiss timer only applies to current attached host`() {
        for (current in listOf(false, true)) for (attached in listOf(false, true))
            assertEquals(current && attached, VolumeOverlayLifecyclePolicy.mayScheduleDismiss(current, attached))
    }
}
