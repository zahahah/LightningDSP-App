package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class NativeControlContextStateTest {
    @Test fun replacementImmediatelyInvalidatesCachedReadiness() {
        val state = NativeControlContextState()
        val first = state.request(null)
        assertTrue(state.record(first.epoch, true))
        assertTrue(state.ready)
        state.invalidate()
        assertFalse(state.ready)
        assertFalse(state.request(null).cached)
    }

    @Test fun oldProbeCannotRestoreReadinessOrConsumeNewCallbacks() {
        val state = NativeControlContextState()
        val delivered = mutableListOf<String>()
        val old = state.request { delivered += "old" }
        state.invalidate()
        val fresh = state.request { delivered += "new" }
        assertTrue(fresh.launch)
        assertFalse(state.record(old.epoch, true))
        assertFalse(state.ready)
        assertTrue(state.finish(old.epoch).isEmpty())
        assertTrue(state.record(fresh.epoch, true))
        state.finish(fresh.epoch).forEach { it(true) }
        assertEquals(listOf("new"), delivered)
        assertTrue(state.ready)
    }

    @Test fun requestsShareOneProbeAndAllCurrentCallbacksComplete() {
        val state = NativeControlContextState()
        var callbacks = 0
        val first = state.request { callbacks++ }
        val second = state.request { callbacks++ }
        assertTrue(first.launch)
        assertFalse(second.launch)
        state.record(first.epoch, false)
        state.finish(first.epoch).forEach { it(false) }
        assertEquals(2, callbacks)
        assertTrue(state.request(null).launch)
    }

    @Test fun cachedDeliveryTokenBecomesStaleOnReplacement() {
        val state = NativeControlContextState()
        val first = state.request(null)
        state.record(first.epoch, true)
        state.finish(first.epoch)
        val cached = state.request { fail("not a pending probe callback") }
        assertTrue(cached.cached)
        state.invalidate()
        assertFalse(state.isCurrent(cached.epoch))
        assertTrue(state.finish(cached.epoch).isEmpty())
    }
}
