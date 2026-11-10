package lightning.dsp.session.root

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import lightning.dsp.utils.extensions.PermissionExtensions.hasPackageUsagePermission
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Observes app foreground transitions only while Session Mode's ready-retention option is enabled. */
internal class ForegroundAppObserver(
    context: Context,
    private val onForegroundPackageChanged: (String?) -> Unit,
    private val intervalMillis: Long = POLL_INTERVAL_MILLIS,
) {
    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(UsageStatsManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "LDSP-foreground-observer").apply { isDaemon = true }
    }
    private var polling: ScheduledFuture<*>? = null
    private var lastQueryTime = 0L
    private var foregroundPackage: String? = null
    private var reportedPackage: String? = null

    @Synchronized fun start() {
        if (polling?.isDone == false) return
        lastQueryTime = 0L
        polling = executor.scheduleWithFixedDelay(::poll, 0, intervalMillis, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun stop() {
        polling?.cancel(true)
        polling = null
        foregroundPackage = null
        lastQueryTime = 0L
        report(null)
    }

    private fun poll() {
        val currentTime = System.currentTimeMillis()
        if (!appContext.hasPackageUsagePermission() || powerManager?.isInteractive == false) {
            foregroundPackage = null
            lastQueryTime = currentTime
            report(null)
            return
        }

        val begin = if (lastQueryTime == 0L) currentTime - INITIAL_LOOKBACK_MILLIS
            else (lastQueryTime - OVERLAP_MILLIS).coerceAtLeast(0L)
        val events = runCatching { usageStats?.queryEvents(begin, currentTime) }.getOrNull()
        if (events == null) {
            foregroundPackage = null
            lastQueryTime = currentTime
            report(null)
            return
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) continue
            val packageName = event.packageName?.takeIf(String::isNotBlank) ?: continue
            when (eventType(event)) {
                RESUMED_EVENT -> foregroundPackage = packageName
                PAUSED_EVENT, STOPPED_EVENT -> if (foregroundPackage == packageName) foregroundPackage = null
            }
        }
        lastQueryTime = currentTime
        report(foregroundPackage)
    }

    private fun report(packageName: String?) {
        if (reportedPackage == packageName) return
        reportedPackage = packageName
        onForegroundPackageChanged(packageName)
    }

    @Suppress("DEPRECATION")
    private fun eventType(event: UsageEvents.Event): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> RESUMED_EVENT
            UsageEvents.Event.ACTIVITY_PAUSED -> PAUSED_EVENT
            UsageEvents.Event.ACTIVITY_STOPPED -> STOPPED_EVENT
            else -> NO_EVENT
        }
        else -> when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> RESUMED_EVENT
            UsageEvents.Event.MOVE_TO_BACKGROUND -> PAUSED_EVENT
            else -> NO_EVENT
        }
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 750L
        private const val INITIAL_LOOKBACK_MILLIS = 72 * 60 * 60 * 1000L
        private const val OVERLAP_MILLIS = 1_000L
        private const val NO_EVENT = 0
        private const val RESUMED_EVENT = 1
        private const val PAUSED_EVENT = 2
        private const val STOPPED_EVENT = 3
    }
}
