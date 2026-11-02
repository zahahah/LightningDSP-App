package lightning.dsp.interop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SupplementalLifecycleTest {
    private class Endpoint(val name: String, var controlled: Boolean = true, var closed: Boolean = false)
    private class Harness {
        val original = Endpoint("original")
        var current: Endpoint? = original
        var generation = 1L
        var barrier = true
        val owned = linkedMapOf<Int, Endpoint>()
        val events = mutableListOf<String>()
        var onFence: suspend (Endpoint) -> Boolean = { true }
        suspend fun release() = SupplementalLifecycle.releaseIfSafe(
            42L, original, { current }, { owned.isNotEmpty() },
            { events += "barrier"; barrier }, { generation }, { it.closed },
            { endpoint, _ -> events += "fence:${endpoint.name}"; onFence(endpoint) },
            { token -> events += "release:$token" },
        )
        fun retire(ids: Collection<Int>) = SupplementalLifecycle.retireOwned(ids, owned,
            { it.controlled }, { events += "disable:${it.name}" },
            { it.closed = true; events += "close:${it.name}" })
    }
    @Test fun teardownRemovesExtrasBeforeBarrierFenceAndResume() = runBlocking {
        val h = Harness()
        val extra = Endpoint("extra")
        h.owned[3] = extra
        assertFalse(h.release())
        assertTrue(h.events.isEmpty())
        h.retire(listOf(3))
        assertTrue(h.release())
        assertEquals(listOf("disable:extra", "close:extra", "barrier", "fence:original", "release:42"), h.events)
        assertFalse(h.original.closed)
    }
    @Test fun barrierFailureKeepsNativeBlockedWithoutFencing() = runBlocking {
        val h = Harness()
        h.barrier = false
        assertFalse(h.release())
        assertEquals(listOf("barrier"), h.events)
    }
    @Test fun inFlightBarrierMustFinishBeforeFenceOrResume() = runBlocking {
        val h = Harness()
        val drained = CompletableDeferred<Boolean>()
        val release = async {
            SupplementalLifecycle.releaseIfSafe(42, h.original, { h.current }, { false },
                { drained.await() }, { h.generation }, { it.closed },
                { _, _ -> h.events += "fence"; true }, { h.events += "release" })
        }
        yield()
        assertTrue(h.events.isEmpty())
        drained.complete(true)
        assertTrue(release.await())
        assertEquals(listOf("fence", "release"), h.events)
    }
    @Test fun failedReplacementFenceCannotResumeUntilSuccessfulRetry() = runBlocking {
        val h = Harness()
        h.current = Endpoint("replacement")
        h.onFence = { it === h.original }
        assertFalse(h.release())
        assertEquals(listOf("barrier", "fence:original", "fence:replacement"), h.events)
        h.onFence = { true }
        assertTrue(h.release())
        assertEquals("release:42", h.events.last())
        assertEquals(1, h.events.count { it.startsWith("release:") })
    }
    @Test fun closedOriginalStillRequiresLiveReplacementFence() = runBlocking {
        val h = Harness()
        h.original.closed = true
        h.current = Endpoint("replacement")
        assertTrue(h.release())
        assertEquals(listOf("barrier", "fence:replacement", "release:42"), h.events)
    }
    @Test fun replacementDuringSuspendingFenceRequiresNewAttempt() = runBlocking {
        val h = Harness()
        h.onFence = { h.current = Endpoint("replacement"); true }
        assertFalse(h.release())
        assertFalse(h.events.any { it.startsWith("release:") })
        h.onFence = { true }
        assertTrue(h.release())
        assertEquals(listOf("fence:original", "fence:replacement", "release:42"), h.events.takeLast(3))
    }
    @Test fun generationChangeOrNewOwnedExtraDuringFenceRefusesResume() = runBlocking {
        val h = Harness()
        h.onFence = { h.generation++; true }
        assertFalse(h.release())
        h.onFence = { h.owned[3] = Endpoint("extra"); true }
        assertFalse(h.release())
        assertFalse(h.events.any { it.startsWith("release:") })
    }
    @Test fun lostControlClosesOnlyOwnedExtraWithoutDisablingForeignEndpoint() {
        val h = Harness()
        val lost = Endpoint("lost", controlled = false)
        val retained = Endpoint("retained")
        val foreign = Endpoint("foreign")
        h.owned[3] = lost
        h.owned[4] = retained
        h.retire(listOf(3, 999))
        assertEquals(listOf("close:lost"), h.events)
        assertEquals(setOf(4), h.owned.keys)
        assertTrue(lost.closed)
        assertFalse(retained.closed)
        assertFalse(foreign.closed)
        assertFalse(h.original.closed)
    }
}
