package lightning.dsp.interop.appmixer

import org.junit.Assert.*
import org.junit.Test

class AppMixerOwnerWatchdogTest {
    private val owner = AppMixerOwnerIdentity(123, 10652, 456L)

    @Test fun procIdentityHandlesSpacesAndParenthesesInComm() {
        val tail = MutableList(20) { "0" }.apply { this[0] = "S"; this[19] = "456" }
        assertEquals(owner, AppMixerOwnerIdentity.parse(123, "123 (owner (app)) ${tail.joinToString(" ")}", "Uid:\t10652\t10652\t10652\t10652"))
        tail[0] = "Z"
        assertThrows(IllegalArgumentException::class.java) {
            AppMixerOwnerIdentity.parse(123, "123 (owner) ${tail.joinToString(" ")}", "Uid: 10652")
        }
    }

    @Test fun liveOwnerDoesNotExpireEvenAfterLongIdle() {
        val watchdog = AppMixerOwnerWatchdog(owner)
        for (now in listOf(0L, 70_000L, 90_000L, 86_400_000L)) {
            assertFalse(watchdog.poll(owner, false, now))
            assertFalse(watchdog.exitRequired(now))
        }
    }

    @Test fun transientReadFailuresDoNotStopOwner() {
        val watchdog = AppMixerOwnerWatchdog(owner)
        assertFalse(watchdog.poll(null, false, 0))
        assertFalse(watchdog.poll(null, false, 1_000))
        assertFalse(watchdog.poll(owner, false, 2_000))
        assertFalse(watchdog.poll(null, false, 3_000))
    }

    @Test fun pidReuseAndOwnerDeathRequestCleanupBeforeSelfExit() {
        for (observed in listOf(null, owner.copy(startTicks = 999), owner.copy(uid = 10001))) {
            val watchdog = AppMixerOwnerWatchdog(owner)
            assertFalse(watchdog.poll(observed, false, 0))
            assertFalse(watchdog.poll(observed, false, 1_000))
            assertTrue(watchdog.poll(observed, false, 2_000))
            assertFalse(watchdog.exitRequired(11_999))
            assertTrue(watchdog.exitRequired(12_000))
        }
    }

    @Test fun explicitStopHasBoundedCleanupWithoutKillingOtherProcesses() {
        val watchdog = AppMixerOwnerWatchdog(owner)
        assertTrue(watchdog.poll(owner, true, 5_000))
        assertFalse(watchdog.exitRequired(14_999))
        assertTrue(watchdog.exitRequired(15_000))
    }
}
