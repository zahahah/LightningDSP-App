package lightning.dsp.volumemanager.ui

/** A monotonic idle deadline that cannot expire while any input source is active. */
internal class VolumeOverlayIdlePolicy(private val timeoutMillis: Long, nowMillis: Long) {
    enum class Interaction { TOUCH, HOVER, FOCUS, ACCESSIBILITY }

    private val active = mutableSetOf<Interaction>()
    private var lastActivityMillis = nowMillis
    private var explicitDismissal = false

    init { require(timeoutMillis > 0 && nowMillis >= 0) }

    fun activity(nowMillis: Long) {
        lastActivityMillis = maxOf(lastActivityMillis, nowMillis)
    }

    fun setInteraction(interaction: Interaction, engaged: Boolean, nowMillis: Long) {
        if (engaged) active.add(interaction) else active.remove(interaction)
        activity(nowMillis)
    }

    fun requestDismissal(explicit: Boolean) {
        explicitDismissal = explicitDismissal || explicit
    }

    fun mayResumeOnInteraction(): Boolean = !explicitDismissal

    /** A new show request may reopen a closing panel without releasing active input. */
    fun reopened(nowMillis: Long) {
        explicitDismissal = false
        activity(nowMillis)
    }

    fun remainingMillis(nowMillis: Long): Long? = if (active.isNotEmpty()) null else
        (timeoutMillis - (nowMillis - lastActivityMillis).coerceAtLeast(0L)).coerceAtLeast(0L)
}
