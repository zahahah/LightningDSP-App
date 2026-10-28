package lightning.dsp.volumemanager.ui

import kotlin.math.abs

/** A slider must yield before acquiring a drag owned by scrolling or long-press editing. */
internal object VolumeTrackGesturePolicy {
    fun shouldYield(
        consumed: Boolean, elapsedMillis: Long, dx: Float, dy: Float,
        touchSlop: Float, longPressMillis: Long, yieldOnLongPress: Boolean = true,
    ): Boolean = consumed || (yieldOnLongPress && elapsedMillis >= longPressMillis) ||
        (abs(dy) > touchSlop && abs(dy) > abs(dx))
}
