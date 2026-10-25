package lightning.dsp.volumemanager

import android.media.AudioManager
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
