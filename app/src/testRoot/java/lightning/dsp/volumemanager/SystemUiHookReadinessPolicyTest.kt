package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.SystemUiHookReadinessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiHookReadinessPolicyTest {
    @Test fun readinessCanOnlyBePublishedByTheLiveSystemUiCaller() {
        assertTrue(SystemUiHookReadinessPolicy.canRegister(
            callerUid = 1000, systemUiUid = 1000, callerPid = 4321, claimedPid = 4321))
        assertFalse(SystemUiHookReadinessPolicy.canRegister(
            callerUid = 1000, systemUiUid = 1000, callerPid = 4321, claimedPid = 1234))
        assertFalse(SystemUiHookReadinessPolicy.canRegister(
            callerUid = 10001, systemUiUid = 1000, callerPid = 4321, claimedPid = 4321))
        assertFalse(SystemUiHookReadinessPolicy.canRegister(
            callerUid = 1000, systemUiUid = 1000, callerPid = 0, claimedPid = 0))
    }

    @Test fun readinessStatusIsLimitedToTheAppRootAndSystemUi() {
        assertTrue(SystemUiHookReadinessPolicy.canRead(callerUid = 0, appUid = 10001, systemUiUid = 1000))
        assertTrue(SystemUiHookReadinessPolicy.canRead(callerUid = 10001, appUid = 10001, systemUiUid = 1000))
        assertTrue(SystemUiHookReadinessPolicy.canRead(callerUid = 1000, appUid = 10001, systemUiUid = 1000))
        assertFalse(SystemUiHookReadinessPolicy.canRead(callerUid = 10002, appUid = 10001, systemUiUid = 1000))
    }
}
