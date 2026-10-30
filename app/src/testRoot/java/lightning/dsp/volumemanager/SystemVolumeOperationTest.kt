package lightning.dsp.volumemanager

import android.media.AudioManager
import android.app.NotificationManager
import lightning.dsp.volumemanager.backend.SystemVolumeOperation
import lightning.dsp.volumemanager.backend.SystemVolumeOperationQueue
import lightning.dsp.volumemanager.backend.SystemVolumeOperationReplayGuard
import org.junit.Assert.*
import org.junit.Test

class SystemVolumeOperationTest {
    @Test fun setOperationsAcceptOnlyKnownStreamsAndIndicesWithinPlatformBounds() {
        val operation = SystemVolumeOperation.set(1, AudioManager.STREAM_MUSIC, 7, 0, 15)
        assertEquals(SystemVolumeOperation.Kind.SET, operation.kind)
        assertEquals(7, operation.value)
        assertThrows(IllegalArgumentException::class.java) {
            SystemVolumeOperation.set(2, AudioManager.STREAM_MUSIC, 16, 0, 15)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemVolumeOperation.set(3, AudioManager.STREAM_ACCESSIBILITY, 4, 0, 15)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemVolumeOperation.set(0, AudioManager.STREAM_MUSIC, 4, 0, 15)
        }
    }

    @Test fun hardwareAdjustmentsAreLimitedToRaiseOrLowerAndARealStream() {
        val defaultStream = SystemVolumeOperation.adjust(1, AudioManager.ADJUST_RAISE)
        val ring = SystemVolumeOperation.adjust(2, AudioManager.ADJUST_LOWER, AudioManager.STREAM_RING)
        assertEquals(AudioManager.USE_DEFAULT_STREAM_TYPE, defaultStream.stream)
        assertEquals(AudioManager.STREAM_RING, ring.stream)
        assertThrows(IllegalArgumentException::class.java) {
            SystemVolumeOperation.adjust(3, AudioManager.ADJUST_MUTE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SystemVolumeOperation.adjust(4, AudioManager.ADJUST_RAISE, AudioManager.STREAM_ACCESSIBILITY)
        }
    }

    @Test fun sliderTargetsCoalescePerStreamWhileKeyIncrementsStayFifo() {
        val queue = SystemVolumeOperationQueue(capacity = 4)
        val firstTarget = SystemVolumeOperation.set(1, AudioManager.STREAM_MUSIC, 3, 0, 15)
        val keyUp = SystemVolumeOperation.adjust(2, AudioManager.ADJUST_RAISE)
        val latestTarget = SystemVolumeOperation.set(3, AudioManager.STREAM_MUSIC, 9, 0, 15)
        val keyDown = SystemVolumeOperation.adjust(4, AudioManager.ADJUST_LOWER)

        assertTrue(queue.offer(firstTarget))
        assertTrue(queue.offer(keyUp))
        assertTrue(queue.offer(latestTarget))
        assertTrue(queue.offer(keyDown))
        assertEquals(3, queue.size)
        assertEquals(keyUp, queue.poll())
        assertEquals(latestTarget, queue.poll())
        assertEquals(keyDown, queue.poll())
        assertNull(queue.poll())
    }

    @Test fun boundedQueueRejectsAdditionalNonCoalescibleAdjustments() {
        val queue = SystemVolumeOperationQueue(capacity = 1)
        assertTrue(queue.offer(SystemVolumeOperation.adjust(1, AudioManager.ADJUST_RAISE)))
        assertFalse(queue.offer(SystemVolumeOperation.adjust(2, AudioManager.ADJUST_RAISE)))
        assertEquals(1, queue.size)
    }

    @Test fun modeCommandsRejectUnknownValuesAndUseNoArbitraryStream() {
        for (mode in listOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)) {
            val operation = SystemVolumeOperation.ringer(10, mode)
            assertEquals(SystemVolumeOperation.Kind.RINGER, operation.kind)
            assertEquals(AudioManager.USE_DEFAULT_STREAM_TYPE, operation.stream)
        }
        for (filter in listOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
            assertEquals(filter, SystemVolumeOperation.interruptionFilter(11, filter).value)
        assertThrows(IllegalArgumentException::class.java) { SystemVolumeOperation.ringer(1, 99) }
        assertThrows(IllegalArgumentException::class.java) { SystemVolumeOperation.ringer(0, AudioManager.RINGER_MODE_NORMAL) }
        assertThrows(IllegalArgumentException::class.java) { SystemVolumeOperation.interruptionFilter(1, 99) }
        assertThrows(IllegalArgumentException::class.java) { SystemVolumeOperation.interruptionFilter(1, NotificationManager.INTERRUPTION_FILTER_NONE) }
    }

    @Test fun explicitModeTargetsCoalesceWithoutDroppingHardwareIncrements() {
        val queue = SystemVolumeOperationQueue(4)
        val old = SystemVolumeOperation.ringer(1, AudioManager.RINGER_MODE_VIBRATE)
        val key = SystemVolumeOperation.adjust(2, AudioManager.ADJUST_RAISE)
        val latest = SystemVolumeOperation.ringer(3, AudioManager.RINGER_MODE_NORMAL)
        val filter = SystemVolumeOperation.interruptionFilter(4, NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertTrue(queue.offer(old))
        assertTrue(queue.offer(key))
        assertTrue(queue.offer(latest))
        assertTrue(queue.offer(filter))
        assertEquals(key, queue.poll())
        assertEquals(latest, queue.poll())
        assertEquals(filter, queue.poll())
        assertNull(queue.poll())
    }

    @Test fun operationReplayGuardRejectsDuplicatesButAllowsIndependentIds() {
        val guard = SystemVolumeOperationReplayGuard(capacity = 2)
        assertTrue(guard.accept(10))
        assertFalse(guard.accept(10))
        assertTrue(guard.accept(11))
        assertTrue(guard.accept(12))
        assertTrue(guard.accept(10)) // bounded history expires the oldest ID
        assertFalse(guard.accept(0))
    }
}
