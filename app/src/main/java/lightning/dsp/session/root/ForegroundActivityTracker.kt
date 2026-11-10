package lightning.dsp.session.root

/**
 * Tracks resumed activities rather than clearing an app's focus on any one
 * ACTIVITY_PAUSED event. An app can pause one Activity while another Activity
 * from the same package (for example, an emulator's player screen) stays resumed.
 */
internal class ForegroundActivityTracker {
    private data class ActivityKey(val packageName: String, val activityId: String)

    private val resumedActivities = linkedMapOf<ActivityKey, Long>()
    private var eventOrder = 0L

    val foregroundPackage: String?
        get() = resumedActivities.maxByOrNull { it.value }?.key?.packageName

    fun onResumed(packageName: String, activityId: String) {
        if (packageName.isBlank()) return
        resumedActivities[ActivityKey(packageName, activityId)] = ++eventOrder
    }

    fun onPaused(packageName: String, activityId: String) {
        if (packageName.isBlank()) return
        resumedActivities.remove(ActivityKey(packageName, activityId))
    }

    fun clear() {
        resumedActivities.clear()
        eventOrder = 0L
    }
}
