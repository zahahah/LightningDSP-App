package lightning.dsp.interop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEffectStartupFenceTest {
    @Test fun doesNotEnableBeforeCompleteStateHasBeenWritten() = runBlocking {
        var enabled = false
        val order = mutableListOf<String>()
        val fence = SessionEffectStartupFence(
            canUseEndpoint = { true },
            isEnabled = { enabled },
            writeCompleteState = { order += "write"; assertFalse(enabled); true },
            setEnabled = { value -> order += "enable:$value"; enabled = value },
        )
        fence.requestEnabled(true)

        assertFalse(enabled)
        assertTrue(fence.prepare(DspDesiredState.current()) { true })
        assertTrue(enabled)
        assertTrue(fence.ready)
        assertTrue(order == listOf("write", "enable:true"))
    }

    @Test fun failedOrObsoleteStateNeverEnablesEffect() = runBlocking {
        var enabled = false
        val fence = SessionEffectStartupFence(
            canUseEndpoint = { true },
            isEnabled = { enabled },
            writeCompleteState = { false },
            setEnabled = { enabled = it },
        )
        fence.requestEnabled(true)

        assertFalse(fence.prepare(DspDesiredState.current()) { true })
        assertFalse(fence.ready)
        assertFalse(enabled)

        val generation = DspDesiredState.current()
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Boolean>()
        val obsoleteFence = SessionEffectStartupFence(
            canUseEndpoint = { true },
            isEnabled = { enabled },
            writeCompleteState = { writeStarted.complete(Unit); finishWrite.await() },
            setEnabled = { enabled = it },
        )
        val preparing = async { obsoleteFence.prepare(generation) { true } }
        writeStarted.await()
        DspDesiredState.replace { }
        finishWrite.complete(true)

        assertFalse(preparing.await())
        assertFalse(obsoleteFence.ready)
        assertFalse(enabled)
    }

    @Test fun preparesInactiveSessionButWaitsForPlaybackEligibility() = runBlocking {
        var enabled = false
        val fence = SessionEffectStartupFence(
            canUseEndpoint = { true },
            isEnabled = { enabled },
            writeCompleteState = { true },
            setEnabled = { enabled = it },
        )

        assertTrue(fence.prepare(DspDesiredState.current()) { false })
        assertTrue(fence.ready)
        assertFalse(enabled)
        fence.requestEnabled(true)
        assertTrue(enabled)
    }
}
