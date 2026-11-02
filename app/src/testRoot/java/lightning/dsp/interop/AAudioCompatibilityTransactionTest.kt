package lightning.dsp.interop

import org.junit.Assert.assertEquals
import org.junit.Test

class AAudioCompatibilityTransactionTest {
    @Test fun verifiedWriteAndCommitAreApplied() {
        val writes = mutableListOf<Boolean>()
        val result = AAudioCompatibilityTransaction.apply(false, true, { writes += it; true }, { true })
        assertEquals(AAudioCompatibilityResult.APPLIED, result)
        assertEquals(listOf(true), writes)
    }

    @Test fun commitFailureAndVerifiedRollbackAreReportedAsFailed() {
        val writes = mutableListOf<Boolean>()
        var stored = false
        var commits = 0
        val result = AAudioCompatibilityTransaction.apply(false, true, { writes += it; true }, {
            stored = it // commit may change memory even when returning false
            ++commits > 1
        })
        assertEquals(AAudioCompatibilityResult.FAILED, result)
        assertEquals(listOf(true, false), writes)
        assertEquals(false, stored)
    }

    @Test fun commitAndRollbackFailureAreUnknownNotOff() {
        val writes = mutableListOf<Boolean>()
        val result = AAudioCompatibilityTransaction.apply(false, true, { writes += it; it }, { false })
        assertEquals(AAudioCompatibilityResult.UNKNOWN, result)
        assertEquals(listOf(true, false), writes)
    }

    @Test fun exceptionAfterWriteAlsoAttemptsRollback() {
        val writes = mutableListOf<Boolean>()
        var commits = 0
        val result = AAudioCompatibilityTransaction.apply(true, false, { writes += it; true }, {
            if (++commits == 1) throw IllegalStateException() else true
        })
        assertEquals(AAudioCompatibilityResult.FAILED, result)
        assertEquals(listOf(false, true), writes)
    }

    @Test fun throwingWriteAndRollbackAreUnknown() {
        val result = AAudioCompatibilityTransaction.apply(false, true, { throw IllegalStateException() }, { true })
        assertEquals(AAudioCompatibilityResult.UNKNOWN, result)
    }
}
