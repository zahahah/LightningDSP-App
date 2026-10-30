package lightning.dsp.volumemanager.ui

/** Capture the target when a slider value is queued, not when its delayed write runs. */
internal data class DspSliderWrite(val namespace: String, val key: String, val value: Float) {
    companion object {
        fun bounded(namespace: String, key: String, value: Float,
                    range: ClosedFloatingPointRange<Float>, default: Float): DspSliderWrite {
            val safeValue = if (value.isFinite()) value.coerceIn(range.start, range.endInclusive)
                else default.coerceIn(range.start, range.endInclusive)
            return DspSliderWrite(namespace, key, safeValue)
        }
        fun dragged(namespace: String, key: String, current: Float, deltaPx: Float,
                    trackWidthPx: Float, range: ClosedFloatingPointRange<Float>, default: Float): DspSliderWrite {
            val delta = if (deltaPx.isFinite() && trackWidthPx.isFinite() && trackWidthPx > 0f)
                deltaPx / trackWidthPx * (range.endInclusive - range.start) else 0f
            return bounded(namespace, key, current + delta, range, default)
        }
    }
}
