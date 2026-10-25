package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumeBridgeIdentity
import org.junit.Assert.*
import org.junit.Test

class VolumeBridgeIdentityTest {
    @Test fun onlyRootAndShellMayExecutePrivilegedEngine() {
        assertTrue(VolumeBridgeIdentity.privileged(0))
        assertTrue(VolumeBridgeIdentity.privileged(2000))
        for (uid in listOf(-1, 1000, 10000, 10123)) assertFalse(VolumeBridgeIdentity.privileged(uid))
    }
    @Test fun callerMustMatchBothInstalledOwnerUidAndPackage() {
        assertTrue(VolumeBridgeIdentity.owns(10123, 10123, "com.lightning.dsp", listOf("com.lightning.dsp")))
        assertFalse(VolumeBridgeIdentity.owns(10124, 10123, "com.lightning.dsp", listOf("com.lightning.dsp")))
        assertFalse(VolumeBridgeIdentity.owns(10123, 10123, "com.lightning.dsp", listOf("other.package")))
        assertFalse(VolumeBridgeIdentity.owns(0, 10123, "com.lightning.dsp", emptyList()))
    }
}
