package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class NativeControlCoverageGateTest {
    @Test fun acquireBlocksImmediatelyButQuiescenceWaitsBehindEarlierTasks() {
        val work = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val gate = NativeControlCoverageGate(work::addLast) { events += "invalidate" }
        work.addLast { events += "inflight-finished" }
        val token = gate.acquire { events += "quiesced:$it" }
        assertTrue(gate.blocked)
        assertEquals(listOf("invalidate"), events)
        while (work.isNotEmpty()) work.removeFirst().invoke()
        assertEquals(listOf("invalidate", "inflight-finished", "quiesced:$token"), events)
    }

    @Test fun staleReleaseCannotUnblockAnotherController() {
        val work = ArrayDeque<() -> Unit>()
        val gate = NativeControlCoverageGate(work::addLast) { }
        val old = gate.acquire { }
        val fresh = gate.acquire { }
        assertFalse(gate.release(old))
        assertTrue(gate.blocked)
        assertFalse(gate.release(old))
        assertTrue(gate.blocked)
        assertTrue(gate.release(fresh))
        assertFalse(gate.blocked)
    }

    @Test fun releasedOwnerDoesNotReceiveStaleQuiescence() {
        val work = ArrayDeque<() -> Unit>()
        val events = mutableListOf<Long>()
        val gate = NativeControlCoverageGate(work::addLast) { }
        val old = gate.acquire(events::add)
        gate.release(old)
        val fresh = gate.acquire(events::add)
        while (work.isNotEmpty()) work.removeFirst().invoke()
        assertEquals(listOf(fresh), events)
    }

    @Test fun leasesInvalidateCachedReadinessAndOldProbeEpochs() {
        val state = NativeControlContextState()
        val work = ArrayDeque<() -> Unit>()
        val gate = NativeControlCoverageGate(work::addLast, state::invalidate)
        val request = state.request(null)
        state.record(request.epoch, true)
        val token = gate.acquire { }
        assertFalse(state.ready)
        assertFalse(state.record(request.epoch, true))
        assertTrue(gate.release(token))
        assertFalse(state.ready)
    }
    @Test fun lastReleaseNeverExposesCachedReadinessDuringInvalidation() {
        val work = ArrayDeque<() -> Unit>()
        var duringInvalidation: (() -> Unit)? = null
        val gate = NativeControlCoverageGate(work::addLast) { duringInvalidation?.invoke() }
        val token = gate.acquire { }
        duringInvalidation = { assertTrue(gate.blocked) }
        assertTrue(gate.release(token))
        assertFalse(gate.blocked)
    }

    @Test fun acquireBlocksBeforeWaitingForDesiredStateInvalidation() {
        val work = ArrayDeque<() -> Unit>()
        lateinit var gate: NativeControlCoverageGate
        gate = NativeControlCoverageGate(work::addLast) { assertTrue(gate.blocked) }
        gate.acquire { }
        assertTrue(gate.blocked)
    }

}
