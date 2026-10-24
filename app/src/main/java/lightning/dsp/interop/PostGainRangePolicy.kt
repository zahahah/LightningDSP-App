package lightning.dsp.interop

/** App-selected range; the native backend accepts at most +/-30 dB. */
object PostGainRangePolicy {
    const val DEFAULT_EXTENDED = false
    const val BACKEND_LIMIT = 30f

    fun limit(extended: Boolean = DEFAULT_EXTENDED): Float = if (extended) BACKEND_LIMIT else 15f

    fun accepts(value: Float, extended: Boolean = DEFAULT_EXTENDED): Boolean =
        value.isFinite() && value in -limit(extended)..limit(extended)

    fun onToggle(value: Float, wasExtended: Boolean, extended: Boolean): Float =
        clamp(clamp(value, wasExtended), extended)

    fun clamp(value: Float, extended: Boolean = DEFAULT_EXTENDED): Float =
        if (value.isFinite()) value.coerceIn(-limit(extended), limit(extended)) else 0f
}
