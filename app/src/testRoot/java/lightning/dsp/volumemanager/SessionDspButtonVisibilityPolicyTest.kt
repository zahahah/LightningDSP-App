package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.SessionDspButtonVisibilityPolicy
import org.junit.Assert.*
import org.junit.Test

class SessionDspButtonVisibilityPolicyTest {
    @Test fun alwaysButtonRequiresSessionModeAndItsSetting() {
        assertTrue(SessionDspButtonVisibilityPolicy.shouldShow(true, true, true, false))
        assertFalse(SessionDspButtonVisibilityPolicy.shouldShow(false, true, true, false))
    }

    @Test fun masterSwitchGatesEveryCombinationIncludingStaleRevealedState() {
        for (mode in listOf(false, true)) for (enabled in listOf(false, true))
            for (always in listOf(false, true)) for (revealed in listOf(false, true)) {
                assertEquals("mode=$mode enabled=$enabled always=$always revealed=$revealed",
                    mode && enabled && (always || revealed),
                    SessionDspButtonVisibilityPolicy.shouldShow(mode, enabled, always, revealed))
            }
    }

    @Test fun iconNeverArmsRevealWhenDisabledOrAlwaysVisible() {
        assertFalse(SessionDspButtonVisibilityPolicy.onIconClick(true, false, false, false))
        assertFalse(SessionDspButtonVisibilityPolicy.onIconClick(true, false, false, true))
        assertFalse(SessionDspButtonVisibilityPolicy.onIconClick(false, true, false, false))
        assertFalse(SessionDspButtonVisibilityPolicy.onIconClick(true, true, true, true))
        assertTrue(SessionDspButtonVisibilityPolicy.onIconClick(true, true, false, false))
        assertFalse(SessionDspButtonVisibilityPolicy.onIconClick(true, true, false, true))
    }

    @Test fun onDemandButtonAppearsOnlyAfterLightningDspIconIsTapped() {
        assertFalse(SessionDspButtonVisibilityPolicy.shouldShow(true, true, false, false))
        assertTrue(SessionDspButtonVisibilityPolicy.shouldShow(true, true, false, true))
        assertFalse(SessionDspButtonVisibilityPolicy.shouldShow(true, false, false, true))
        assertFalse(SessionDspButtonVisibilityPolicy.shouldShow(true, false, false, false))
    }
}
