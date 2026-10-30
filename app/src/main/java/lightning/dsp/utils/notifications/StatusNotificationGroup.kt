package lightning.dsp.utils.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import lightning.dsp.R
import lightning.dsp.utils.Constants
import lightning.dsp.utils.isRootless

/** One status group; both foreground notifications keep their own content and navigation. */
object StatusNotificationGroup {
    const val KEY = "lightningdsp_status"
    const val VOLUME_ID = 7391
    const val SUMMARY_ID = 7392

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val refreshToken = Any()
    private val stoppedIds = mutableSetOf<Int>()
    private val settleDelaysMs = longArrayOf(150L, 650L)

    internal data class Plan(val groupedIds: Set<Int>, val showSummary: Boolean)

    internal fun plan(activeIds: Set<Int>): Plan {
        val services = setOf(Notifications.ID_SERVICE_STATUS, VOLUME_ID)
        val bothPresent = activeIds.containsAll(services)
        return Plan(if (bothPresent) services else emptySet(), bothPresent)
    }

    /** Explicit lifecycle authority rejects stale Android snapshots after an in-process stop. */
    @Synchronized
    fun serviceStarted(context: Context, id: Int) {
        stoppedIds.remove(id)
        refresh(context)
    }

    @Synchronized
    fun serviceStopped(context: Context, id: Int) {
        stoppedIds.add(id)
        // Serialize the final cancel after any in-flight reconciliation of an older snapshot.
        context.getSystemService(NotificationManager::class.java).cancel(id)
        refresh(context)
    }

    internal fun liveServiceIds(activeIds: Set<Int>, stoppedIds: Set<Int>): Set<Int> =
        activeIds.intersect(setOf(Notifications.ID_SERVICE_STATUS, VOLUME_ID)) - stoppedIds

    /** NMS posts/cancels are queued. Re-read after bounded settling, without self-polling. */
    @Synchronized
    fun refresh(context: Context) {
        if (isRootless()) return
        val application = context.applicationContext
        reconcile(application)
        handler.removeCallbacksAndMessages(refreshToken)
        settleDelaysMs.forEach { delay ->
            handler.postAtTime({ reconcile(application) }, refreshToken, SystemClock.uptimeMillis() + delay)
        }
    }

    /** Query Android, not only cached service state, to survive process/service recreation. */
    @Synchronized
    private fun reconcile(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val snapshot = manager.activeNotifications
        val liveIds = liveServiceIds(snapshot.map { it.id }.toSet(), stoppedIds)
        val active = snapshot.filter { it.id in liveIds }
        val plan = plan(active.map { it.id }.toSet())
        if (plan.showSummary) {
            val dsp = active.first { it.id == Notifications.ID_SERVICE_STATUS }.notification
            val mixer = active.first { it.id == VOLUME_ID }.notification
            // Reuse an existing visible service channel without overriding its user settings.
            val channel = if (manager.getNotificationChannel(dsp.channelId)?.importance != NotificationManager.IMPORTANCE_NONE)
                dsp.channelId else mixer.channelId
            val enabled = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
                .getBoolean(context.getString(R.string.key_powered_on), true)
            val dspTitle = dsp.extras.getCharSequence(Notification.EXTRA_TITLE)
            val mixerTitle = mixer.extras.getCharSequence(Notification.EXTRA_TITLE)
            manager.notify(SUMMARY_ID, Notification.Builder(context, channel)
                .setSmallIcon(processingNotificationIcon(enabled))
                .setContentTitle(dspTitle)
                .setContentText(listOfNotNull(dspTitle, mixerTitle).joinToString(" • "))
                .setStyle(Notification.InboxStyle().addLine(dspTitle).addLine(mixerTitle))
                .setContentIntent(dsp.contentIntent)
                .setGroup(KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .build())
        }
        active.forEach { entry ->
            val notification = entry.notification
            val group = if (entry.id in plan.groupedIds) KEY else null
            val wasSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            if (notification.group != group || wasSummary) {
                manager.notify(entry.id, Notification.Builder.recoverBuilder(context, notification)
                    .setGroup(group)
                    .setGroupSummary(false)
                    .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                    .setOnlyAlertOnce(true)
                    .build())
            }
        }
        // Detach the surviving foreground child before removing the summary: no group cascade.
        if (!plan.showSummary) manager.cancel(SUMMARY_ID)
    }
}
