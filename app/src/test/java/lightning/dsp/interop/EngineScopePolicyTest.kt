package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class EngineScopePolicyTest {
    @Test fun onlyOrdinaryGlobalCanUseSingletonNativeControl() {
        assertTrue(EngineScopePolicy.usesUnscopedNative(0, false))
        for (session in listOf(-2, -1, 1, 169, Int.MAX_VALUE)) {
            assertFalse(EngineScopePolicy.usesUnscopedNative(session, false))
            assertFalse(EngineScopePolicy.usesUnscopedNative(session, true))
        }
        assertFalse(EngineScopePolicy.usesUnscopedNative(0, true))
    }

    @Test fun supplementalRateAndCloseZeroCannotOverwriteGlobalRate() {
        val published = mutableListOf<Float>()
        EngineScopePolicy.publishSampleRate(true, 48000f, published::add)
        EngineScopePolicy.publishSampleRate(false, 44100f, published::add)
        EngineScopePolicy.publishSampleRate(false, 0f, published::add)
        assertEquals(listOf(48000f), published)
        EngineScopePolicy.publishSampleRate(true, 0f, published::add)
        assertEquals(listOf(48000f, 0f), published)
    }
}
