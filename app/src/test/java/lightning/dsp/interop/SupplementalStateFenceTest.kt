package lightning.dsp.interop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupplementalStateFenceTest {
    private class Endpoint {
        var owned = true
        var controlled = true
        var leased = true
        var enabled = false
        val events = mutableListOf<String>()
        fun fence(writer: suspend (Long) -> Boolean) = SupplementalStateFence(
            canUseEndpoint = { owned && controlled && leased },
            isEnabled = { enabled },
            writeCompleteState = writer,
            enable = { events += "enable"; enabled = true },
        )
    }

    @Test fun failedNamespaceNeverAdmitsOrEnablesPartialState() = runBlocking {
        val endpoint = Endpoint()
        val namespaces = listOf("output", "eq", "convolver", "liveprog")
        for (failed in namespaces) {
            val fence = endpoint.fence {
                namespaces.map { namespace ->
                    endpoint.events += namespace
                    namespace != failed
                }.all { it }
            }
            val generation = DspDesiredState.current()
            assertFalse(fence.prepare(generation))
            assertFalse(fence.admitted)
            assertFalse(fence.activate(generation))
            assertFalse(endpoint.enabled)
            assertFalse(endpoint.events.contains("enable"))
        }
    }

    @Test fun preparationIsAwaitedAndOnlySuccessfulCurrentStateEnables() = runBlocking {
        val endpoint = Endpoint()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fence = endpoint.fence {
            endpoint.events += "output"
            started.complete(Unit)
            finish.await()
            endpoint.events += "convolver"
            true
        }
        val generation = DspDesiredState.current()
        val prepare = async { fence.prepare(generation) }
        started.await()
        assertFalse(fence.activate(generation))
        assertFalse(endpoint.enabled)
        finish.complete(Unit)
        assertTrue(prepare.await())
        assertTrue(fence.activate(generation))
        assertEquals(listOf("output", "convolver", "enable"), endpoint.events)
        assertTrue(endpoint.enabled)
    }

    @Test fun generationChangeDuringSuspendedWriterRefusesActivation() = runBlocking {
        val endpoint = Endpoint()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fence = endpoint.fence { started.complete(Unit); finish.await(); true }
        val generation = DspDesiredState.current()
        val prepare = async { fence.prepare(generation) }
        started.await()
        DspDesiredState.replace { }
        finish.complete(Unit)
        assertFalse(prepare.await())
        assertFalse(fence.activate(generation))
        assertFalse(fence.activate(DspDesiredState.current()))
        assertFalse(endpoint.enabled)
    }

    @Test fun generationChangeAfterPreparationCannotEnableObsoleteState() = runBlocking {
        val endpoint = Endpoint()
        val fence = endpoint.fence { true }
        val generation = DspDesiredState.current()
        assertTrue(fence.prepare(generation))
        DspDesiredState.replace { }
        assertFalse(fence.activate(generation))
        assertFalse(fence.activate(DspDesiredState.current()))
        assertFalse(endpoint.enabled)
    }

    @Test fun ownershipControlAndLeaseAreRecheckedAtActivation() = runBlocking {
        for (lost in listOf("ownership", "control", "lease")) {
            val endpoint = Endpoint()
            val fence = endpoint.fence { true }
            val generation = DspDesiredState.current()
            assertTrue(fence.prepare(generation))
            when (lost) {
                "ownership" -> endpoint.owned = false
                "control" -> endpoint.controlled = false
                "lease" -> endpoint.leased = false
            }
            assertFalse(fence.activate(generation))
            assertFalse(endpoint.enabled)
        }
    }

    @Test fun invalidatedPreparationCannotBeActivated() = runBlocking {
        val endpoint = Endpoint()
        val fence = endpoint.fence { true }
        val generation = DspDesiredState.current()
        assertTrue(fence.prepare(generation))
        fence.invalidate()
        assertFalse(fence.activate(generation))
        assertFalse(endpoint.enabled)
    }
}
