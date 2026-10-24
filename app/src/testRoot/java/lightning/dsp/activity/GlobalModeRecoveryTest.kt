package lightning.dsp.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalModeRecoveryTest {
    @Test fun attachesMissingGlobalSession() {
        assertTrue(shouldReattachGlobalMode(true, false, false))
        assertTrue(shouldReattachGlobalMode(true, true, true))
    }

    @Test fun doesNotChangePerAppModeOrDuplicateAnExistingSession() {
        assertFalse(shouldReattachGlobalMode(false, false, true))
        assertFalse(shouldReattachGlobalMode(true, true, false))
    }
}
