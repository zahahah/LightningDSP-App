package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumePipeSession
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VolumePipeSessionLifecycleTest {
    private class Child(private val blocked: Boolean) : Process() {
        val writing = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                writing.countDown()
                if (blocked) releaseWrite.await()
                synchronized(bytes) { bytes.write(value) }
            }
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int { if (exited.count != 0L) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { destroyed.countDown() }
        override fun destroyForcibly(): Process { destroyed.countDown(); return this }
    }

    @Test fun privilegeDenialBeforeActivationDoesNotLatchCleanupUncertainty() {
        val child = Child(false)
        val session = VolumePipeSession(child, {}, {})
        try {
            assertFalse(session.cleanupUncertain)
            // Seeing ready is not enough: the host records authorization before it
            // enqueues activate=true. Once authorized, final cleanup proof is required.
            session.authorizeSideEffects()
            assertTrue(session.cleanupUncertain)
            session.confirmCleanup(true)
            assertFalse(session.cleanupUncertain)
        } finally { child.exited.countDown(); session.stop() }
    }

    @Test fun blockedWriterDoesNotBlockStopWatchdogOrConfirmAnUnexitedChild() {
        val child = Child(true)
        val exited = CountDownLatch(1)
        val session = VolumePipeSession(child, { exited.countDown() }, { fail(it.toString()) }, graceMillis = 20)
        try {
            session.offer("first")
            assertTrue(child.writing.await(2, TimeUnit.SECONDS))
            session.stop()
            assertTrue("Watchdog must not need the writer lock", child.destroyed.await(2, TimeUnit.SECONDS))
            assertEquals(VolumePipeSession.Phase.STOPPING, session.phase)
            assertEquals(1L, exited.count)
            session.offer("must-not-restart")
            child.exited.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertEquals(VolumePipeSession.Phase.STOPPED, session.phase)
        } finally { child.releaseWrite.countDown(); child.exited.countDown() }
    }

    @Test fun pendingGesturesAreConflatedNotReplayed() {
        val child = Child(true)
        val session = VolumePipeSession(child, {}, { fail(it.toString()) })
        try {
            session.offer("first")
            assertTrue(child.writing.await(2, TimeUnit.SECONDS))
            repeat(10_000) { session.offer("gesture-$it") }
            child.releaseWrite.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline && synchronized(child.bytes) { !child.bytes.toString().contains("gesture-9999") }) Thread.yield()
            val written = synchronized(child.bytes) { child.bytes.toString() }
            assertTrue(written.contains("gesture-9999"))
            assertFalse(written.contains("gesture-9998"))
        } finally { child.exited.countDown(); child.releaseWrite.countDown(); session.stop() }
    }

    @Test fun brokenPipeConvergesOnStopping() {
        val child = Child(false)
        val failed = CountDownLatch(1)
        val broken = object : Process() {
            override fun getOutputStream() = object : OutputStream() { override fun write(value: Int) { throw java.io.IOException("closed") } }
            override fun getInputStream() = child.inputStream
            override fun getErrorStream() = child.errorStream
            override fun waitFor() = child.waitFor()
            override fun waitFor(timeout: Long, unit: TimeUnit) = child.waitFor(timeout, unit)
            override fun exitValue() = child.exitValue()
            override fun destroy() = child.destroy()
            override fun destroyForcibly(): Process { child.destroyForcibly(); return this }
        }
        val session = VolumePipeSession(broken, {}, { failed.countDown() }, graceMillis = 20)
        try {
            session.offer("settings")
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(child.destroyed.await(2, TimeUnit.SECONDS))
            assertEquals(VolumePipeSession.Phase.STOPPING, session.phase)
        } finally { child.exited.countDown() }
    }
}
