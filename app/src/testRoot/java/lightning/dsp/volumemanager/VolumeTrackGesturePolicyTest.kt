package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumeTrackGesturePolicy
import lightning.dsp.volumemanager.ui.trackTextClipRight
import org.junit.Assert.*
import org.junit.Test

class VolumeTrackGesturePolicyTest {
    private fun yields(consumed: Boolean = false, elapsed: Long = 100, dx: Float = 30f, dy: Float = 2f) =
        VolumeTrackGesturePolicy.shouldYield(consumed, elapsed, dx, dy, 8f, 500)

    @Test fun ordinaryHorizontalDragStillAdjustsVolume() {
        assertFalse(yields())
    }
    @Test fun consumedAndLongPressDiagonalReordersNeverAcquireSliderDrag() {
        assertTrue(yields(consumed = true))
        assertTrue(yields(elapsed = 500, dx = 80f, dy = 40f))
        assertTrue(yields(elapsed = 900, dx = 0f, dy = 20f))
    }
    @Test fun verticalScrollingCannotBecomeATapOrLateVolumeDrag() {
        assertTrue(yields(dx = 2f, dy = 20f))
    }
    @Test fun floatingDspTrackCanStartAHorizontalDragAfterAStationaryHold() {
        assertFalse(VolumeTrackGesturePolicy.shouldYield(false, 750, 30f, 2f, 8f, 500,
            yieldOnLongPress = false))
        assertTrue(VolumeTrackGesturePolicy.shouldYield(false, 750, 2f, 20f, 8f, 500,
            yieldOnLongPress = false))
        assertTrue(VolumeTrackGesturePolicy.shouldYield(true, 750, 30f, 2f, 8f, 500,
            yieldOnLongPress = false))
    }
    @Test fun textContrastChangesExactlyAtTheTrackFillBoundary() {
        assertEquals(0f, trackTextClipRight(168f, 16f, 0f), 0.001f)
        assertEquals(4f, trackTextClipRight(168f, 16f, 0.1f), 0.001f)
        assertEquals(84f, trackTextClipRight(168f, 16f, 0.5f), 0.001f)
        assertEquals(164f, trackTextClipRight(168f, 16f, 0.9f), 0.001f)
        assertEquals(168f, trackTextClipRight(168f, 16f, 1f), 0.001f)
    }
}
