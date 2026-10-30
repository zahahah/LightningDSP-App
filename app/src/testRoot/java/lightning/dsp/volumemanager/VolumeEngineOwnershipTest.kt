package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumeEngineOwnership
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class VolumeEngineOwnershipTest {
    @Test fun secondOwnerCannotBeginUntilFirstCleanupReleasesLease() {
        var held = false
        val acquire = {
            check(!held) { "Another engine owns playback control" }
            held = true
            Closeable { held = false }
        }
        val old = VolumeEngineOwnership(acquire)
        val next = VolumeEngineOwnership(acquire)
        old.acquire()
        assertTrue(runCatching { next.acquire() }.isFailure)
        // First owner's cleanup runs while it still holds exclusion.
        assertTrue(held)
        old.close()
        next.acquire()
        assertTrue(held)
        next.close()
        assertFalse(held)
    }
    @Test fun failedAcquisitionNeverClosesAnotherOwner() {
        var released = false
        val active = VolumeEngineOwnership { Closeable { released = true } }
        active.acquire()
        val lease = VolumeEngineOwnership { throw IllegalStateException("busy") }
        assertTrue(runCatching { lease.acquire() }.isFailure)
        lease.close()
        assertFalse(released)
        active.close()
        assertTrue(released)
    }
}
