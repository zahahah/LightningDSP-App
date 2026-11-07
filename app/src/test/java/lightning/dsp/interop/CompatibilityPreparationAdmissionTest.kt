package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class CompatibilityPreparationAdmissionTest {
    @Test fun expiredOrMismatchedRequestsCannotAuthorizePreparation() {
        assertTrue(CompatibilityPreparationAdmission.current(10001, 10001, 2000, 1999))
        assertFalse(CompatibilityPreparationAdmission.current(10001, 10001, 2000, 2000))
        assertFalse(CompatibilityPreparationAdmission.current(10001, 10001, 2000, 2001))
        assertFalse(CompatibilityPreparationAdmission.current(10001, 10002, 2000, 1999))
    }
    @Test fun noisyCallerDoesNotConsumeAnotherCallersAdmission() {
        val gate = CompatibilityPreparationAdmission()
        assertTrue(gate.admit(10001, 0))
        assertFalse(gate.admit(10001, 999))
        assertTrue(gate.admit(10002, 999))
        assertTrue(gate.admit(10001, 1000))
    }
    @Test fun successfulSerialOpensCanPrepareAgainWithoutCooldown() {
        val gate = CompatibilityPreparationAdmission()
        assertTrue(gate.admit(10001, 0))
        gate.succeeded(10001, 0)
        assertTrue(gate.admit(10001, 1))
        gate.succeeded(10001, 0) // stale completion must not clear newer admission
        assertFalse(gate.admit(10001, 2))
    }
    @Test fun bookkeepingIsBoundedAndExpires() {
        val gate = CompatibilityPreparationAdmission()
        repeat(64) { assertTrue(gate.admit(10000 + it, 0)) }
        assertFalse(gate.admit(20000, 1000))
        assertTrue(gate.admit(20000, 60000))
    }
}
