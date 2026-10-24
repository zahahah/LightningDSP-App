package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class PostGainRangePolicyTest {
    @Test fun defaultRangeIsNormalAndIncludesBothBounds() {
        assertFalse(PostGainRangePolicy.DEFAULT_EXTENDED)
        assertEquals(15f, PostGainRangePolicy.limit(), 0f)
        for (value in listOf(-15f, 0f, 15f)) assertTrue(PostGainRangePolicy.accepts(value))
        for (value in listOf(-15.01f, 15.01f, -30f, 30f)) assertFalse(PostGainRangePolicy.accepts(value))
    }

    @Test fun extendedRangeStillHasFiniteBackendBounds() {
        assertEquals(30f, PostGainRangePolicy.limit(true), 0f)
        for (value in listOf(-30f, -20f, 20f, 30f)) assertTrue(PostGainRangePolicy.accepts(value, true))
        for (value in listOf(-30.01f, 30.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(PostGainRangePolicy.accepts(value, true))
            assertFalse(PostGainRangePolicy.accepts(value))
        }
    }

    @Test fun invalidStoredValuesHaveSafeDefaultsAndFiniteValuesClamp() {
        for (extended in listOf(false, true)) {
            val limit = PostGainRangePolicy.limit(extended)
            assertEquals(-limit, PostGainRangePolicy.clamp(-100f, extended), 0f)
            assertEquals(limit, PostGainRangePolicy.clamp(100f, extended), 0f)
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
                assertEquals(0f, PostGainRangePolicy.clamp(value, extended), 0f)
        }
    }

    @Test fun togglesUseEffectiveGainEvenWhenStorageWasExternallyChanged() {
        assertEquals(15f, PostGainRangePolicy.onToggle(25f, false, true), 0f)
        assertEquals(-15f, PostGainRangePolicy.onToggle(-30f, false, true), 0f)
        assertEquals(15f, PostGainRangePolicy.onToggle(25f, true, false), 0f)
        assertEquals(0f, PostGainRangePolicy.onToggle(Float.NaN, false, true), 0f)
    }

    @Test fun enablingPreservesGainAndDisablingClampsWithoutResettingNormalGain() {
        for (value in listOf(-15f, -4f, 0f, 8f, 15f)) {
            assertEquals(value, PostGainRangePolicy.onToggle(value, false, true), 0f)
            assertEquals(value, PostGainRangePolicy.onToggle(value, true, false), 0f)
        }
        assertEquals(15f, PostGainRangePolicy.clamp(30f, false), 0f)
        assertEquals(-15f, PostGainRangePolicy.clamp(-30f, false), 0f)
    }
}
