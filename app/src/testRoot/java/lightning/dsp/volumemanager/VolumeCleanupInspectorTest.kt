package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumeCleanupInspector
import org.junit.Assert.*
import org.junit.Test

class VolumeCleanupInspectorTest {
    @Test fun matchingCompleteProofFromSuccessfulInspectorIsAccepted() {
        val child = ProcessBuilder("sh", "-c", "cat >/dev/null; printf 'expected-proof\\n'")
            .redirectErrorStream(true).start()
        assertTrue(VolumeCleanupInspector.inspect(child, "journal\n", "expected-proof"))
    }
    @Test fun partialProofOrFailedExitDoesNotAuthorizeRecovery() {
        for (command in listOf("cat >/dev/null; printf expected-proof", "cat >/dev/null; printf 'expected-proof\\n'; exit 1")) {
            assertFalse(VolumeCleanupInspector.inspect(ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true).start(), "journal\n", "expected-proof"))
        }
    }
    @Test fun blockedInputCannotHoldTheWorkerIndefinitely() {
        // Busy shell is the owned inspector itself; no unrelated process is terminated.
        val child = ProcessBuilder("sh", "-c", "while :; do :; done").redirectErrorStream(true).start()
        val start = System.nanoTime()
        assertFalse(VolumeCleanupInspector.inspect(child, "x".repeat(131072), "proof", 100))
        assertTrue(TimeUnitMillis(System.nanoTime() - start) < 3000)
    }
    @Test fun oversizedOutputIsRejectedWithoutPipeDeadlock() {
        val child = ProcessBuilder("sh", "-c", "cat >/dev/null; i=0; while [ ${'$'}i -lt 2000 ]; do echo diagnostic; i=${'$'}((i+1)); done; echo expected-proof")
            .redirectErrorStream(true).start()
        assertFalse(VolumeCleanupInspector.inspect(child, "journal\n", "expected-proof", 1000))
    }
    @Test fun blockedDestroyCannotBlockTheSerializedWorker() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val child = object : Process() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun waitFor(): Int = error("unbounded wait must not be used")
            override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = false
            override fun exitValue(): Int = throw IllegalThreadStateException()
            override fun destroy() { entered.countDown(); release.await() }
        }
        try {
            val start = System.nanoTime()
            assertFalse(VolumeCleanupInspector.inspect(child, "journal", "proof", 10))
            assertTrue(TimeUnitMillis(System.nanoTime() - start) < 1000)
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown() }
    }
    private fun TimeUnitMillis(nanos: Long) = nanos / 1_000_000
}
