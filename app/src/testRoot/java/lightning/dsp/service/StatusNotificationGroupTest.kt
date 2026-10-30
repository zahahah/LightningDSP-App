package lightning.dsp.service

import java.io.File
import lightning.dsp.utils.notifications.Notifications
import lightning.dsp.utils.notifications.StatusNotificationGroup
import org.junit.Assert.*
import org.junit.Test

class StatusNotificationGroupTest {
    private val dsp = Notifications.ID_SERVICE_STATUS
    private val mixer = StatusNotificationGroup.VOLUME_ID
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }

    @Test fun allPresenceSubsetsOnlyGroupWhenBothForegroundRowsExist() {
        listOf(emptySet(), setOf(dsp), setOf(mixer), setOf(dsp, mixer)).forEach { active ->
            val plan = StatusNotificationGroup.plan(active)
            val both = dsp in active && mixer in active
            assertEquals(both, plan.showSummary)
            assertEquals(if (both) setOf(dsp, mixer) else emptySet<Int>(), plan.groupedIds)
        }
        assertFalse(StatusNotificationGroup.plan(setOf(StatusNotificationGroup.SUMMARY_ID)).showSummary)
        assertFalse(StatusNotificationGroup.plan(setOf(Notifications.ID_SERVICE_STARTUP, mixer)).showSummary)
    }

    @Test fun bothStartupStopAndRestartSequencesKeepIndependentForegroundIds() {
        listOf(
            listOf(emptySet(), setOf(mixer), setOf(mixer, dsp), setOf(dsp), emptySet()),
            listOf(emptySet(), setOf(dsp), setOf(dsp, mixer), setOf(mixer), setOf(mixer, dsp)),
        ).forEach { sequence ->
            sequence.forEach { active ->
                val plan = StatusNotificationGroup.plan(active)
                assertFalse(StatusNotificationGroup.SUMMARY_ID in plan.groupedIds)
                assertEquals(active.size == 2, plan.showSummary)
                if (!plan.showSummary) assertTrue(plan.groupedIds.isEmpty())
            }
        }
    }

    @Test fun staleSnapshotsCannotReviveStoppedForegroundIdsButRestartClearsTombstone() {
        val staleSnapshot = setOf(dsp, mixer, StatusNotificationGroup.SUMMARY_ID)
        assertEquals(setOf(mixer), StatusNotificationGroup.liveServiceIds(staleSnapshot, setOf(dsp)))
        assertEquals(setOf(dsp), StatusNotificationGroup.liveServiceIds(staleSnapshot, setOf(mixer)))
        assertTrue(StatusNotificationGroup.liveServiceIds(staleSnapshot, setOf(dsp, mixer)).isEmpty())
        // A fresh process or explicit service start has no tombstone for the live service.
        assertEquals(setOf(dsp, mixer), StatusNotificationGroup.liveServiceIds(staleSnapshot, emptySet()))
    }

    @Test fun reconciliationCoalescesTwoBoundedFreshReadsWithoutSelfPollingOrActivityCapture() {
        val coordinator = File(app,
            "src/main/java/lightning/dsp/utils/notifications/StatusNotificationGroup.kt").readText()
        assertTrue(coordinator.contains("longArrayOf(150L, 650L)"))
        assertTrue(coordinator.contains("val application = context.applicationContext"))
        assertTrue(coordinator.contains("handler.removeCallbacksAndMessages(refreshToken)"))
        assertTrue(coordinator.contains("handler.postAtTime({ reconcile(application) }, refreshToken, SystemClock.uptimeMillis() + delay)"))
        val reconcile = coordinator.substringAfter("private fun reconcile(context: Context)")
        assertFalse(reconcile.contains("postAtTime"))
        assertFalse(reconcile.contains("refresh(context)"))
        assertTrue(coordinator.contains("stoppedIds.remove(id)"))
        assertTrue(coordinator.contains("stoppedIds.add(id)"))
        val stop = coordinator.substringAfter("fun serviceStopped").substringBefore("internal fun liveServiceIds")
        assertTrue(stop.indexOf("stoppedIds.add(id)") < stop.indexOf(".cancel(id)"))
        assertTrue(stop.indexOf(".cancel(id)") < stop.indexOf("refresh(context)"))
    }

    @Test fun allNotificationUpdatesAndBothServiceLifecyclesRefreshTheLiveGroup() {
        val helper = File(app,
            "src/main/java/lightning/dsp/utils/notifications/ServiceNotificationHelper.kt").readText()
        assertEquals(2, Regex("StatusNotificationGroup.refresh\\(context\\)").findAll(helper).count())
        val dspService = File(app, "src/main/java/lightning/dsp/service/RootAudioProcessorService.kt").readText()
        val mixerService = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeService.kt").readText()
        assertTrue(dspService.contains("StatusNotificationGroup.serviceStarted(this, Notifications.ID_SERVICE_STATUS)"))
        assertTrue(mixerService.contains("StatusNotificationGroup.serviceStarted(this, StatusNotificationGroup.VOLUME_ID)"))
        assertTrue(dspService.contains("notificationManager.cancel(Notifications.ID_SERVICE_STATUS)\n        StatusNotificationGroup.serviceStopped(this, Notifications.ID_SERVICE_STATUS)"))
        assertTrue(mixerService.contains("cancel(StatusNotificationGroup.VOLUME_ID)\n        StatusNotificationGroup.serviceStopped(this, StatusNotificationGroup.VOLUME_ID)"))
    }

    @Test fun groupingPreservesExistingNotificationsChannelsAndRootlessBehavior() {
        val coordinator = File(app,
            "src/main/java/lightning/dsp/utils/notifications/StatusNotificationGroup.kt").readText()
        assertTrue(coordinator.contains("if (isRootless()) return"))
        assertTrue(coordinator.contains("manager.activeNotifications"))
        assertTrue(coordinator.contains("Notification.Builder.recoverBuilder(context, notification)"))
        assertTrue(coordinator.contains(".setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)"))
        assertFalse(coordinator.contains("createNotificationChannel"))
        assertTrue(coordinator.contains("@Synchronized\n    fun refresh"))
        assertTrue(coordinator.contains(".setContentIntent(dsp.contentIntent)"))
        assertTrue(coordinator.contains(".setSmallIcon(processingNotificationIcon(enabled))"))
        assertTrue(coordinator.contains(".setGroupSummary(false)"))
        assertTrue(coordinator.indexOf("active.forEach") < coordinator.indexOf("manager.cancel(SUMMARY_ID)"))
        val helper = File(app,
            "src/main/java/lightning/dsp/utils/notifications/ServiceNotificationHelper.kt").readText()
        assertFalse(helper.contains("setGroupSummary(true)"))
    }
}
