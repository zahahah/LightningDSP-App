package lightning.dsp.interop.appmixer

import org.junit.Assert.*
import org.junit.Test
import lightning.dsp.session.shared.SessionAdmissionGate
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class AppMixerSupervisorTest {
    private class Helper(
        private val acknowledge: Boolean = true,
        private val failBeforeRoute: Boolean = false,
        private val rejectUpdate: Boolean = false,
    ) : Process() {
        private val responses = PipedInputStream()
        private val responseWriter = PipedOutputStream(responses)
        private val requests = PipedInputStream()
        private val requestWriter = PipedOutputStream(requests)
        private val done = CountDownLatch(1)
        val commands = mutableListOf<String>()
        init {
            Thread {
                if (failBeforeRoute) {
                    responseWriter.write("APP_MIXER:FAILED AudioFlinger inventory layout unknown\n".toByteArray())
                    responseWriter.write("APP_MIXER:RELEASED (preflight failed before route registration)\n".toByteArray())
                    responseWriter.flush()
                    responseWriter.close()
                    done.countDown()
                    return@Thread
                }
                responseWriter.write("APP_MIXER:SESSION 42\n".toByteArray()); responseWriter.flush()
                val input = requests.bufferedReader()
                val first = input.readLine()
                if (first != null) commands.add(first)
                if (first == "READY") {
                    responseWriter.write("APP_MIXER:ROUTING\n".toByteArray()); responseWriter.flush()
                    responseWriter.write("APP_MIXER:RUNNING session=42\n".toByteArray()); responseWriter.flush()
                    while (true) {
                        val next = input.readLine() ?: break
                        commands.add(next)
                        if (next == "STOP") break
                        if (next.startsWith("UPDATE ")) {
                            val id = next.split(' ')[1]
                            responseWriter.write("APP_MIXER:${if (rejectUpdate) "UPDATE_FAILED" else "UPDATED"} $id\n".toByteArray())
                            responseWriter.flush()
                        }
                    }
                }
                if (acknowledge) { responseWriter.write("APP_MIXER:RELEASED (verify)\n".toByteArray()); responseWriter.flush() }
                responseWriter.close(); done.countDown()
            }.apply { isDaemon = true; start() }
        }
        override fun getOutputStream() = requestWriter
        override fun getInputStream() = responses
        override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { done.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = done.await(timeout, unit)
        override fun exitValue(): Int { if (done.count != 0L) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { requestWriter.close() }
    }

    @Test fun helperIsStartedOnlyAfterOtherEnginesAreStoppedAndEffectPrepared() {
        val events = mutableListOf<String>()
        val helper = Helper()
        val supervisor = AppMixerSupervisor({ events += "launch"; helper },
            { events += "stop others"; AutoCloseable { events += "release fence" } }, { session ->
                assertEquals(42, session); events += "prepare"
                AutoCloseable { events += "release" }
            })
        assertTrue(supervisor.start())
        assertFalse(supervisor.start())
        supervisor.close()
        assertEquals(listOf("stop others", "launch", "prepare", "release", "release fence"), events)
        assertEquals(listOf("READY", "STOP"), helper.commands)
    }

    @Test fun refusedExclusivityNeverLaunchesHelper() {
        var launched = false
        val supervisor = AppMixerSupervisor({ launched = true; Helper() }, { null }, { null })
        assertFalse(supervisor.start())
        assertFalse(launched)
        assertEquals("exclusive handoff was refused", supervisor.lastFailureReason)
    }

    @Test fun helperFailureBeforeRoutingReleasesExclusiveLease() {
        val helper = Helper(failBeforeRoute = true)
        var released = false
        val supervisor = AppMixerSupervisor({ helper }, { AutoCloseable { released = true } }, { AutoCloseable { } })
        assertFalse(supervisor.start())
        assertEquals("AudioFlinger inventory layout unknown", supervisor.lastFailureReason)
        assertTrue(released)
        assertFalse(supervisor.hasOwner())
    }

    @Test fun ruleUpdateKeepsSameRelayAndLease() {
        val helper = Helper()
        var launches = 0
        var released = false
        val supervisor = AppMixerSupervisor({ launches++; helper }, { AutoCloseable { released = true } }, { AutoCloseable {} })
        assertTrue(supervisor.start())
        assertTrue(supervisor.updateExclusions(setOf(0, 10004, 10322)))
        assertEquals(1, launches)
        assertFalse(released)
        assertEquals(listOf("READY", "UPDATE 1 0 0,10004,10322"), helper.commands)
        supervisor.close()
        assertTrue(released)
    }

    @Test fun rejectedRuleUpdateClosesRouteRatherThanClaimingSelectionApplied() {
        val helper = Helper(rejectUpdate = true)
        var released = false
        val supervisor = AppMixerSupervisor({ helper }, { AutoCloseable { released = true } }, { AutoCloseable {} })
        assertTrue(supervisor.start())
        assertFalse(supervisor.updateExclusions(setOf(0, 10004, 10322)))
        assertTrue(released)
        assertFalse(supervisor.hasOwner())
    }

    @Test fun failedEffectPreparationStopsHelperWithoutSendingReady() {
        val helper = Helper()
        val supervisor = AppMixerSupervisor({ helper }, { AutoCloseable { } }, { null })
        assertFalse(supervisor.start())
        assertEquals(listOf("STOP"), helper.commands)
    }
    @Test fun missingCleanupAcknowledgementRetainsFenceAndRefusesRestart() {
        val helper = Helper(acknowledge = false)
        var released = false
        val supervisor = AppMixerSupervisor({ helper }, { AutoCloseable { released = true } },
            { AutoCloseable { } })
        assertTrue(supervisor.start())
        supervisor.close()
        assertFalse(released)
        assertTrue(supervisor.hasOwner())
        assertFalse(supervisor.start())
    }

    @Test fun failedEffectReleaseRetainsExclusiveFence() {
        val helper = Helper()
        var released = false
        val supervisor = AppMixerSupervisor({ helper }, { AutoCloseable { released = true } },
            { AutoCloseable { throw IllegalStateException("release failed") } })
        assertTrue(supervisor.start())
        supervisor.close()
        assertFalse(released)
        assertTrue(supervisor.hasOwner())
        assertFalse(supervisor.start())
    }

    @Test fun completeHandshakeKeepsSessionAdmissionFencedUntilCleanup() {
        val gate = SessionAdmissionGate()
        val helper = Helper()
        val supervisor = AppMixerSupervisor({ helper }, { gate.acquire {} }, { AutoCloseable {} })
        assertTrue(supervisor.start())
        assertFalse(gate.runIfAllowed { fail("competing session was admitted") })
        supervisor.close()
        assertTrue(gate.runIfAllowed {})
    }

    @Test fun unexpectedHelperExitIsReportedAfterCleanup() {
        val helper = Helper()
        val exited = CountDownLatch(1)
        var clean = false
        val supervisor = AppMixerSupervisor({ helper }, { AutoCloseable {} }, { AutoCloseable {} }) {
            clean = it
            exited.countDown()
        }
        assertTrue(supervisor.start())
        helper.destroy()
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        assertTrue(clean)
        assertFalse(supervisor.isActive())
    }

    @Test fun changedEligibilityBeforeReadyStopsWithoutRouting() {
        val helper = Helper()
        var released = false
        val supervisor = AppMixerSupervisor({ helper }, { AutoCloseable {} },
            { AutoCloseable { released = true } }, { false })
        assertFalse(supervisor.start())
        assertEquals(listOf("STOP"), helper.commands)
        assertTrue(released)
    }

}
