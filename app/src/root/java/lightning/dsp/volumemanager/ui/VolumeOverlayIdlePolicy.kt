package lightning.dsp.volumemanager.ui

/** A monotonic idle deadline that cannot expire while any input source is active. */
internal class VolumeOverlayIdlePolicy(private val timeoutMillis: Long, nowMillis: Long) {
    enum class Interaction { TOUCH, HOVER, FOCUS, ACCESSIBILITY }

    private val active = mutableSetOf<Interaction>()
    private var lastActivityMillis = nowMillis

    init { require(timeoutMillis > 0 && nowMillis >= 0) }

    fun activity(nowMillis: Long) {
        lastActivityMillis = maxOf(lastActivityMillis, nowMillis)
    }

    fun setInteraction(interaction: Interaction, engaged: Boolean, nowMillis: Long) {
        if (engaged) active.add(interaction) else active.remove(interaction)
        activity(nowMillis)
    }

    fun remainingMillis(nowMillis: Long): Long? = if (active.isNotEmpty()) null else
        (timeoutMillis - (nowMillis - lastActivityMillis).coerceAtLeast(0L)).coerceAtLeast(0L)
}
