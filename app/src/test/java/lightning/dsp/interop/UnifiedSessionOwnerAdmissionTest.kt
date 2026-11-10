package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class UnifiedSessionOwnerAdmissionTest {
    @Test fun callbackIdentityMustMatchActualCaller() {
        assertTrue(UnifiedSessionOwnerAdmission.matches(10123, 42, 10123, 42))
        assertFalse(UnifiedSessionOwnerAdmission.matches(10123, 42, 10124, 42))
        assertFalse(UnifiedSessionOwnerAdmission.matches(10123, 42, 10123, 43))
        assertFalse(UnifiedSessionOwnerAdmission.matches(10123, 0, 10123, 0))
    }
    @Test fun missingAckDeathAndDeadlineFailClosed() {
        assertTrue(UnifiedSessionOwnerAdmission.accepts(true, true, false, 10, 11))
        assertFalse(UnifiedSessionOwnerAdmission.accepts(false, true, false, 10, 11))
        assertFalse(UnifiedSessionOwnerAdmission.accepts(true, false, false, 10, 11))
        assertFalse(UnifiedSessionOwnerAdmission.accepts(true, true, true, 10, 11))
        assertFalse(UnifiedSessionOwnerAdmission.accepts(true, true, false, 11, 11))
        assertFalse(UnifiedSessionOwnerAdmission.accepts(true, true, false, 12, 11))
    }
}
