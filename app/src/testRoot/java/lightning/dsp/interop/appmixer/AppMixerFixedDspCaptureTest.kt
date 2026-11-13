package lightning.dsp.interop.appmixer

import org.junit.Assert.*
import org.junit.Test

class AppMixerFixedDspCaptureTest {
    @Test fun partialAndEmptyReadsProduceOneFixedQuantumWithoutLosingSamples() {
        var calls = 0
        val capture = AppMixerFixedDspCapture(4) { input, output, count ->
            calls++
            assertEquals(4, count)
            for (i in 0 until count) output[i] = (input[i] * 2).toShort()
        }
        val output = ShortArray(4)
        var next = 1
        for (fragment in listOf(1, 0, 2, 1, 4)) {
            val result = capture.read(output) { data, offset, requested ->
                assertTrue(fragment <= requested)
                for (i in 0 until fragment) data[offset + i] = (next++).toShort()
                fragment
            }
            if (result > 0) {
                assertEquals(4, result)
                val first = if (calls == 1) 2 else 10
                assertArrayEquals(ShortArray(4) { (first + it * 2).toShort() }, output)
            }
        }
        assertEquals(2, calls)
    }

    @Test fun errorDoesNotProcessPartialOrStalePcm() {
        val capture = AppMixerFixedDspCapture(4) { _, _, _ -> fail("Incomplete quantum") }
        val output = ShortArray(4)
        assertEquals(0, capture.read(output) { _, _, _ -> 2 })
        assertEquals(-6, capture.read(output) { _, _, _ -> -6 })
    }

    @Test fun invalidSizesAndOverreadsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AppMixerFixedDspCapture(3) { _, _, _ -> } }
        val capture = AppMixerFixedDspCapture(4) { _, _, _ -> }
        assertThrows(IllegalStateException::class.java) { capture.read(ShortArray(4)) { _, _, _ -> 5 } }
        assertThrows(IllegalArgumentException::class.java) { capture.read(ShortArray(2)) { _, _, _ -> 0 } }
    }
}
