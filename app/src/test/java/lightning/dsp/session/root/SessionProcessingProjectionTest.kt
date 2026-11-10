package lightning.dsp.session.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProcessingProjectionTest {
    @Test fun includesPlaybackAndVerifiedFocusedRetentionOnlyWhenProcessingIsEnabled() {
        assertTrue(SessionProcessingProjection.includes(473, true, playing = true, focusedRetention = false))
        assertTrue(SessionProcessingProjection.includes(473, true, playing = false, focusedRetention = true))
        assertFalse(SessionProcessingProjection.includes(473, true, playing = false, focusedRetention = false))
        assertFalse(SessionProcessingProjection.includes(473, false, playing = true, focusedRetention = true))
        assertFalse(SessionProcessingProjection.includes(0, true, playing = true, focusedRetention = true))
    }
}
