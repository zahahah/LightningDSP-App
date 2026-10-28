package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.DspSliderWrite
import org.junit.Assert.assertEquals
import org.junit.Test

class DspSliderWriteTest {
    @Test fun queuedWriteRetainsItsOriginalEffectTargetAfterSelectionChanges() {
        val bass = DspSliderWrite.bounded("bass", "max_gain", 12f, 3f..15f, 5f)
        val tube = DspSliderWrite.bounded("tube", "drive", -1f, -3f..12f, 2f)
        assertEquals("bass", bass.namespace)
        assertEquals("max_gain", bass.key)
        assertEquals(12f, bass.value)
        assertEquals("tube", tube.namespace)
        assertEquals("drive", tube.key)
        assertEquals(-1f, tube.value)
    }

    @Test fun nonFiniteAndOutOfRangeValuesCannotReachEffectPreferences() {
        assertEquals(5f, DspSliderWrite.bounded("bass", "max_gain", Float.NaN, 3f..15f, 5f).value)
        assertEquals(15f, DspSliderWrite.bounded("bass", "max_gain", 99f, 3f..15f, 5f).value)
    }
}
