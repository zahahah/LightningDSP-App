package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.SessionDspButtonVisibilityPolicy
import org.junit.Assert.*
import org.junit.Test

class SessionDspButtonVisibilityPolicyTest {
    @Test fun alwaysButtonRequiresSessionModeAndItsSetting() {
        assertTrue(SessionDspButtonVisibilityPolicy.shouldShow(true, true, true, false))
        assertFalse(SessionDspButtonVisibilityPolicy.shouldShow(false, true, true, false))
    }

    @Test fun onDemandButtonAppearsOnlyAfterLightningDspIconIsTapped() {
        assertFalse(SessionDspButtonVisibilityPolicy.shouldShow(true, true, false, false))
        assertTrue(SessionDspButtonVisibilityPolicy.shouldShow(true, true, false, true))
        assertTrue(SessionDspButtonVisibilityPolicy.shouldShow(true, false, false, true))
        assertFalse(SessionDspButtonVisibilityPolicy.shouldShow(true, false, false, false))
    }
}
