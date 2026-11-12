package lightning.dsp.session.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlaybackPreparationLeaseTest {
    private val lease = SessionPlaybackPreparationLease(10322, "com.spotify.music", 2_000)

    @Test fun onlyTheMatchingLiveSessionMayUseThePreStartLease() {
        assertTrue(lease.isCurrent(41, 10322, "com.spotify.music", 1_999))
        assertFalse(lease.isCurrent(0, 10322, "com.spotify.music", 1_999))
        assertFalse(lease.isCurrent(41, 10323, "com.spotify.music", 1_999))
        assertFalse(lease.isCurrent(41, 10322, "com.other.player", 1_999))
    }

    @Test fun leaseExpiresAtItsBoundedDeadline() {
        assertFalse(lease.isCurrent(41, 10322, "com.spotify.music", 2_000))
        assertFalse(lease.isCurrent(41, 10322, "com.spotify.music", 2_001))
    }
}
