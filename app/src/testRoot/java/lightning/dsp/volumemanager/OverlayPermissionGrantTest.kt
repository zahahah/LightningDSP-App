package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.OverlayPermissionGrant
import org.junit.Assert.*
import org.junit.Test

class OverlayPermissionGrantTest {
    @Test fun grantedPermissionDoesNotRequestRoot() {
        assertTrue(OverlayPermissionGrant.grant("com.lightning.dsp", 0, { true },
            { error("root must not be requested") }, { error("no mutation") }))
    }
    @Test fun deniedRootDoesNotExecuteCommand() {
        assertFalse(OverlayPermissionGrant.grant("com.lightning.dsp", 0, { false },
            { false }, { error("no mutation") }))
    }
    @Test fun acceptedCommandWithoutEffectivePermissionIsNotSuccess() {
        assertFalse(OverlayPermissionGrant.grant("com.lightning.dsp", 0, { false }, { true }, { true }))
    }
    @Test fun grantsOnlyOwnPackageForTheCallingUserAndRechecks() {
        var allowed = false
        assertTrue(OverlayPermissionGrant.grant("com.lightning.dsp", 10, { allowed }, { true }, {
            assertEquals("cmd appops set --user 10 com.lightning.dsp SYSTEM_ALERT_WINDOW allow", it)
            allowed = true
            true
        }))
    }
    @Test fun failedCommandNeverReportsSuccess() {
        assertFalse(OverlayPermissionGrant.grant("com.lightning.dsp", 0, { false }, { true }, { false }))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOtherPackages() {
        OverlayPermissionGrant.grant("another.app", 0, { false }, { true }, { true })
    }
}
