package lightning.dsp.interop.appmixer

import org.junit.Assert.*
import org.junit.Test

class AppMixerBufferedRelayTest {
    @Test fun blockedWritesDoNotImmediatelyStopCaptureAndQueueIsBounded() {
        var reads = 0
        var idles = 0
        val counts = AppMixerPcmRelay.runBuffered(ShortArray(4), { idles < 3 },
            { reads++; 4 }, { _, _, _ -> 0 }, { idles++ })
        assertEquals(4, reads)
        assertEquals(AppMixerPcmRelay.Counts(16, 0), counts)
    }

    @Test fun wraparoundAndPartialWritesPreserveExactSampleOrder() {
        var captured = 0
        val output = mutableListOf<Short>()
        var writes = 0
        val counts = AppMixerPcmRelay.runBuffered(ShortArray(4), { output.size < 60 }, { block ->
            val count = minOf(4, 60 - captured)
            for (i in 0 until count) block[i] = (captured + i).toShort()
            captured += count
            count
        }, { block, offset, count ->
            writes++
            val size = if (writes % 4 == 0) 0 else minOf(count, 3)
            for (i in 0 until size) output.add(block[offset + i])
            size
        }, {})
        assertEquals((0 until 60).map { it.toShort() }, output)
        assertEquals(AppMixerPcmRelay.Counts(60, 60), counts)
    }

    @Test fun emptyInputSleepsWithoutWritingOrFabricatingAudio() {
        var idles = 0
        val counts = AppMixerPcmRelay.runBuffered(ShortArray(4), { idles < 3 }, { 0 },
            { _, _, _ -> fail("No PCM to write"); 0 }, { idles++ })
        assertEquals(AppMixerPcmRelay.Counts(0, 0), counts)
    }

    @Test fun stalledOutputStillTimesOutAndCancellationDoesNotWaitForDrain() {
        var now = 0L
        assertThrows(IllegalStateException::class.java) {
            AppMixerPcmRelay.runBuffered(ShortArray(4), { true }, { 4 }, { _, _, _ -> 0 },
                { now += 5 }, { now }, 10)
        }
        var running = true
        val counts = AppMixerPcmRelay.runBuffered(ShortArray(4), { running },
            { running = false; 4 }, { _, _, _ -> fail("Cancelled"); 0 }, {})
        assertEquals(AppMixerPcmRelay.Counts(4, 0), counts)
    }

    @Test fun negativeReadOrWriteFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            AppMixerPcmRelay.runBuffered(ShortArray(4), { true }, { -6 }, { _, _, _ -> 0 }, {})
        }
        assertThrows(IllegalStateException::class.java) {
            AppMixerPcmRelay.runBuffered(ShortArray(4), { true }, { 4 }, { _, _, _ -> -6 }, {})
        }
    }

    @Test fun diagnosticLinesAreBoundedAndRecoverAfterOversizedInput() {
        val lines = mutableListOf<String>()
        AppMixerProcessStreamDrainer.drain(("x".repeat(2000) + "\nokay\n").byteInputStream(), lines::add)
        assertEquals(listOf("okay"), lines)
    }
}
