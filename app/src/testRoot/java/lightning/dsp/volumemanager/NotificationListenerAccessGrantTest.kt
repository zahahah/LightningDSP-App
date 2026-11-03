package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.NotificationListenerAccessGrant
import org.junit.Assert.*
import org.junit.Test

class NotificationListenerAccessGrantTest {
    @Test fun existingAccessDoesNotNeedRootOrCommand() {
        assertTrue(NotificationListenerAccessGrant.grant(0, { true },
            { error("root not needed") }, { error("already granted") }))
    }

    @Test fun deniedRootDoesNotChangeAccess() {
        assertFalse(NotificationListenerAccessGrant.grant(0, { false }, { false },
            { error("command must not run") }))
    }

    @Test fun explicitlyGrantsOnlyLightningDspListenerForCurrentUserAndRechecks() {
        var granted = false
        assertTrue(NotificationListenerAccessGrant.grant(10, { granted }, { true }, { command ->
            assertEquals("cmd notification allow_listener com.lightning.dsp/me.timschneeberger.rootlessjamesdsp.service.NotificationListenerService 10", command)
            granted = true
            true
        }))
    }

    @Test fun commandSuccessWithoutEffectiveAccessIsNotSuccess() {
        assertFalse(NotificationListenerAccessGrant.grant(0, { false }, { true }, { true }))
    }

    @Test fun commandFailureIsNotSuccess() {
        assertFalse(NotificationListenerAccessGrant.grant(0, { false }, { true }, { false }))
    }
}
