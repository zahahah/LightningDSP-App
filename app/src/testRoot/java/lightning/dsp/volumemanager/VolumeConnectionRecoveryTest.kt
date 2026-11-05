package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumeConnectionRecovery
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VolumeConnectionRecoveryTest {
    @Test fun spontaneousFailuresUseBoundedBackoff() {
        val recovery = VolumeConnectionRecovery()
        assertEquals(1_000L, recovery.nextDelayMillis(true, true, true))
        assertEquals(3_000L, recovery.nextDelayMillis(true, true, true))
        assertEquals(10_000L, recovery.nextDelayMillis(true, true, true))
        repeat(10) { assertNull(recovery.nextDelayMillis(true, true, true)) }
    }

    @Test fun disabledDetachedOrUnverifiedCleanupCannotStartOrConsumeRetry() {
        val recovery = VolumeConnectionRecovery()
        assertNull(recovery.nextDelayMillis(false, true, true))
        assertNull(recovery.nextDelayMillis(true, false, true))
        assertNull(recovery.nextDelayMillis(true, true, false))
        assertEquals(1_000L, recovery.nextDelayMillis(true, true, true))
    }

    @Test fun briefConnectionsDoNotCreateAnInfiniteRestartLoop() {
        val recovery = VolumeConnectionRecovery()
        repeat(3) {
            assertNotNull(recovery.nextDelayMillis(true, true, true))
            recovery.healthyResponse(1_000)
            recovery.healthyResponse(59_000)
            recovery.disconnected()
        }
        assertNull(recovery.nextDelayMillis(true, true, true))
    }

    @Test fun sustainedHealthyResponsesRestoreTheRetryBudget() {
        val recovery = VolumeConnectionRecovery()
        repeat(3) { recovery.nextDelayMillis(true, true, true) }
        recovery.healthyResponse(10_000)
        recovery.healthyResponse(70_000)
        recovery.disconnected()
        assertEquals(1_000L, recovery.nextDelayMillis(true, true, true))
    }

    @Test fun explicitResetStartsANewLifecycle() {
        val recovery = VolumeConnectionRecovery()
        repeat(3) { recovery.nextDelayMillis(true, true, true) }
        recovery.reset()
        assertEquals(1_000L, recovery.nextDelayMillis(true, true, true))
    }

    @Test fun clockRollbackDoesNotRestoreBudgetPrematurely() {
        val recovery = VolumeConnectionRecovery()
        repeat(3) { recovery.nextDelayMillis(true, true, true) }
        recovery.healthyResponse(10_000)
        recovery.healthyResponse(0)
        recovery.healthyResponse(59_999)
        assertNull(recovery.nextDelayMillis(true, true, true))
    }

    @Test fun reconnectIsOwnerBoundAndDetachCancelsItsFuture() {
        val source = source("LightningDspVolumeManager.kt")
        val reconnect = source.substringAfter("private fun scheduleReconnect()")
            .substringBefore("private fun closeProcess()")
        assertTrue(reconnect.contains("serviceOwner === owner"))
        assertTrue(reconnect.contains("process == null && !cleanupUncertain"))
        assertTrue(reconnect.contains("requireCurrentOwner = true"))
        val detach = source.substringAfter("internal fun detach(")
            .substringBefore("private fun newCleanupNonce()")
        assertTrue(detach.contains("serviceOwner = null"))
        assertTrue(detach.contains("cancelReconnect(resetBudget = true)"))
    }

    @Test fun pipeClocksExcludeDeepSleepWithoutChangingMediaRetentionClock() {
        val manager = source("LightningDspVolumeManager.kt")
        assertTrue(manager.contains("lastResponseAt = SystemClock.uptimeMillis()"))
        assertTrue(manager.contains("SystemClock.uptimeMillis() - lastResponseAt >= timeout"))
        assertFalse(manager.contains("SystemClock.elapsedRealtime() - lastResponseAt"))
        val root = source("LightningDspVolumeRoot.kt")
        assertTrue(root.contains("VolumeHostLease(SystemClock.uptimeMillis())"))
        assertTrue(root.contains("hostLease.renew(SystemClock.uptimeMillis())"))
        assertTrue(root.contains("hostLease.expired(SystemClock.uptimeMillis())"))
        assertTrue(manager.contains("mediaRetention.contains(name, now)"))
        assertTrue(manager.contains("val now = SystemClock.elapsedRealtime()"))
    }

    private fun source(name: String): String = sequenceOf(File("."), File("app"))
        .map { File(it, "src/root/java/lightning/dsp/volumemanager/backend/$name") }
        .first { it.isFile }.readText()
}
