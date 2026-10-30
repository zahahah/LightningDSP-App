package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class NativeControlWriteDispatcherTest {
    @Test fun aQueuedMainThreadUpdateDoesNotAlsoUseLegacy() {
        val jobs = mutableListOf<() -> Unit>()
        var nativeWrites = 0
        var fallbackWrites = 0
        val dispatcher = NativeControlWriteDispatcher({ true }, { jobs.add(it) }) { _, _ ->
            nativeWrites++
            NativeControlReply.parse("LDSPCTL:OK:accepted=7:applied=6:pending=1")
        }
        assertTrue(dispatcher.dispatch("fir", listOf("enabled")) { fallbackWrites++ })
        assertEquals(0, nativeWrites)
        jobs.single().invoke()
        assertEquals(1, nativeWrites)
        assertEquals(0, fallbackWrites) // Pending is not a reason to overwrite it.
    }

    @Test fun aFailedNativeWriteUsesFallbackExactlyOnce() {
        val jobs = mutableListOf<() -> Unit>()
        var fallbackWrites = 0
        val dispatcher = NativeControlWriteDispatcher({ true }, { jobs.add(it) }) { _, _ ->
            NativeControlReply.parse("LDSPCTL:ERR:service-unavailable-or-find-denied")
        }
        assertTrue(dispatcher.dispatch("fir", emptyList()) { fallbackWrites++ })
        jobs.single().invoke()
        assertEquals(1, fallbackWrites)
    }

    @Test fun obsoleteQueuedControlsDoNotOverwriteTheNewestValue() {
        val jobs = mutableListOf<() -> Unit>()
        val written = mutableListOf<String>()
        val dispatcher = NativeControlWriteDispatcher({ true }, { jobs.add(it) }) { _, args ->
            written.add(args.single())
            NativeControlReply.parse("LDSPCTL:OK:accepted=8:applied=8:pending=0")
        }
        dispatcher.dispatch("post-gain", listOf("old")) { fail("obsolete fallback") }
        dispatcher.dispatch("post-gain", listOf("new")) { fail("native accepted") }
        jobs.forEach { it() }
        assertEquals(listOf("new"), written)
    }

    @Test fun unavailableNativeAllowsTheCallerToChooseLegacy() {
        var ready = true
        val jobs = mutableListOf<() -> Unit>()
        val dispatcher = NativeControlWriteDispatcher({ ready }, { jobs.add(it) }) { _, _ ->
            fail("stale write must be cancelled")
            null
        }
        dispatcher.dispatch("fir", listOf("old")) { fail("stale fallback") }
        ready = false
        assertFalse(dispatcher.dispatch("fir", listOf("new")) {})
        jobs.single().invoke()
    }
}
