package lightning.dsp.service

import org.junit.Assert.*
import org.junit.Test

class SessionCloseGraceTest {
    @Test fun reopenInvalidatesPendingCloseAndNewCloseGetsNewGeneration() {
        val grace = SessionCloseGrace()
        val firstClose = grace.schedule(41)
        assertTrue(grace.isCurrent(41, firstClose))

        grace.cancel(41)
        assertFalse(grace.isCurrent(41, firstClose))
        val secondClose = grace.schedule(41)
        assertNotEquals(firstClose, secondClose)
        assertFalse(grace.isCurrent(41, firstClose))
        assertTrue(grace.isCurrent(41, secondClose))
    }

    @Test fun staleCompletionCannotClearANewerClose() {
        val grace = SessionCloseGrace()
        val firstClose = grace.schedule(41)
        val secondClose = grace.schedule(41)

        grace.complete(41, firstClose)
        assertTrue(grace.isCurrent(41, secondClose))
        grace.complete(41, secondClose)
        assertFalse(grace.isCurrent(41, secondClose))
    }
}
