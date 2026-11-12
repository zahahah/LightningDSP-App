package lightning.dsp.session.shared

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SessionAdmissionGateTest {
    @Test fun retirementWaitsForInFlightAdmissionAndBlocksNewAdmission() {
        val gate = SessionAdmissionGate()
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val retired = CountDownLatch(1)
        var lease: AutoCloseable? = null
        val admission = thread { gate.runIfAllowed { entered.countDown(); leave.await() } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val acquire = thread { lease = gate.acquire { retired.countDown() } }
        assertFalse(retired.await(50, TimeUnit.MILLISECONDS))
        leave.countDown(); admission.join(); acquire.join()
        assertTrue(retired.await(1, TimeUnit.SECONDS))
        assertTrue(gate.isBlocked())
        assertFalse(gate.runIfAllowed { fail("must remain fenced") })
        lease!!.close()
        assertFalse(gate.isBlocked())
        assertTrue(gate.runIfAllowed { })
    }

    @Test fun oldLeaseCannotReleaseNewOwnerAndRetirementFailureFailsClosed() {
        val gate = SessionAdmissionGate()
        val first = gate.acquire {}!!
        first.close()
        val second = gate.acquire {}!!
        first.close()
        assertFalse(gate.runIfAllowed {})
        second.close()
        assertThrows(IllegalStateException::class.java) { gate.acquire { error("retirement failed") } }
        assertFalse(gate.runIfAllowed {})
    }
}
