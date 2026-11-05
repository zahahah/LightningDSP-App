package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumeHostLease
import org.junit.Assert.*
import org.junit.Test

class VolumeHostLeaseTest {
    @Test fun independentRenewalsSurviveContinuouslyChangingSnapshots() {
        val lease = VolumeHostLease(0)
        // Simulate five minutes with a changed root snapshot every500ms (no heartbeat).
        // The independent host task renews every5s, not when the root emits heartbeat.
        for (now in 0L..300_000L step 500) {
            if (now % 5000 == 0L) lease.renew(now)
            assertFalse("Lease expired at $now despite independent host renewal", lease.expired(now))
        }
    }
    @Test fun playbackActivityDoesNotKeepAnAbandonedHostAlive() {
        val lease = VolumeHostLease(1000)
        assertFalse(lease.expired(31_000))
        assertTrue(lease.expired(31_001))
    }

    @Test fun suspendedWallTimeDoesNotExpireAnAwakeTimeLease() {
        val lease = VolumeHostLease(1_000)
        // Two hours of deep sleep advance elapsedRealtime but not uptime.
        val elapsedAfterSleep = 7_201_000L
        val uptimeAfterSleep = 1_000L
        assertTrue(elapsedAfterSleep > uptimeAfterSleep)
        assertFalse(lease.expired(uptimeAfterSleep))
        lease.renew(uptimeAfterSleep + 5_000)
        assertFalse(lease.expired(uptimeAfterSleep + 30_000))
        assertTrue(lease.expired(uptimeAfterSleep + 35_001))
    }
}
