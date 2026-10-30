package lightning.dsp.volumemanager.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bound the entire private verifier exchange, including blocked su/input/output pipes. */
internal object VolumeCleanupInspector {
    fun inspect(process: Process, journal: String, proof: String, timeoutMillis: Long = 7000): Boolean {
        val failed = AtomicBoolean(false)
        val confirmed = AtomicBoolean(false)
        val finished = CountDownLatch(2)
        Thread({
            try {
                process.outputStream.use { it.write(journal.toByteArray(Charsets.UTF_8)) }
            } catch (_: Exception) { failed.set(true) }
            finally { finished.countDown() }
        }, "LightningDSP-cleanup-input").apply { isDaemon = true; start() }
        Thread({
            try {
                process.inputStream.use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (bytes.size() <= 8192) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                    if (bytes.size() > 8192) failed.set(true)
                    else confirmed.set(bytes.toString("UTF-8").substringBeforeLast('\n', "")
                        .lineSequence().any { it == proof })
                }
            } catch (_: Exception) { failed.set(true) }
            finally { finished.countDown() }
        }, "LightningDSP-cleanup-output").apply { isDaemon = true; start() }
        return try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) &&
                finished.await(1, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                !failed.get() && confirmed.get()
        } finally {
            // Android destroy()/stream close can contend with blocked pipe I/O. Never
            // perform teardown on the serialized playback worker or wait for this daemon.
            Thread({
                if (process.isAlive) runCatching { process.destroy() }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }, "LightningDSP-cleanup-teardown").apply { isDaemon = true; start() }
        }
    }
}
