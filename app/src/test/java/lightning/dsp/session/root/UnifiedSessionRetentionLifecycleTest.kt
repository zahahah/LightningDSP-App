package lightning.dsp.session.root

import org.junit.Assert.*
import org.junit.Test

class UnifiedSessionRetentionLifecycleTest {
    @Test fun stoppedServiceRejectsNewAndPreviouslyQueuedWork() {
        val lifecycle = UnifiedSessionRetentionLifecycle()
        assertNull(lifecycle.current())
        lifecycle.start()
        val old = lifecycle.current()!!
        assertTrue(lifecycle.accepts(old))
        lifecycle.stop()
        assertNull(lifecycle.current())
        assertFalse(lifecycle.accepts(old))
    }
    @Test fun restartedServiceDoesNotReviveOldProviderCallbacks() {
        val lifecycle = UnifiedSessionRetentionLifecycle()
        lifecycle.start()
        val old = lifecycle.current()!!
        lifecycle.stop()
        lifecycle.start()
        val current = lifecycle.current()!!
        assertNotEquals(old, current)
        assertFalse(lifecycle.accepts(old))
        assertTrue(lifecycle.accepts(current))
    }
}
