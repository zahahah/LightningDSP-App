package lightning.dsp.interop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlaybackPreparationAdmissionTest {
    @Test fun distinctSessionsFromTheSameAppAreNotThrottled() {
        val admission = SessionPlaybackPreparationAdmission()
        assertTrue(admission.admit(10322, 41, 0))
        assertTrue(admission.admit(10322, 42, 1))
    }

    @Test fun repeatedPreparationForTheSameSessionCanJoinItsCurrentStartup() {
        val admission = SessionPlaybackPreparationAdmission()
        assertTrue(admission.admit(10322, 41, 0))
        assertTrue(admission.admit(10322, 41, 1))
    }

    @Test fun bookkeepingIsBoundedAndExpiredEntriesCanBeReused() {
        val admission = SessionPlaybackPreparationAdmission(maxEntries = 2, retentionMillis = 10)
        assertTrue(admission.admit(10322, 41, 0))
        assertTrue(admission.admit(10322, 42, 0))
        assertFalse(admission.admit(10322, 43, 1))
        assertTrue(admission.admit(10322, 43, 10))
    }
}
