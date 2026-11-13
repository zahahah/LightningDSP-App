package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class NativeDspHandlePolicyTest {
    @Test fun taggedArm64PointersAreValidEvenWithSignedLongRepresentation() {
        val tagged = java.lang.Long.parseUnsignedLong("b400001234567890", 16)
        assertTrue(tagged < 0)
        assertTrue(NativeDspHandlePolicy.isValid(tagged))
        assertTrue(NativeDspHandlePolicy.isValid(0x1234567890L))
    }

    @Test fun onlyNullAndLegacyErrorSentinelsAreRejected() {
        for (value in listOf(0L, 1L, 2L)) assertFalse(NativeDspHandlePolicy.isValid(value))
        assertTrue(NativeDspHandlePolicy.isValid(Long.MIN_VALUE))
        assertTrue(NativeDspHandlePolicy.isValid(-1L))
    }
}
