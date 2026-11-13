package lightning.dsp.interop.appmixer

import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one helper and its effect. Call only on a background thread. */
internal class AppMixerSupervisor(
    private val launch: () -> Process,
    private val acquireExclusive: () -> AutoCloseable?,
    private val prepareEffect: (Int) -> AutoCloseable?,
    private val mayRoute: () -> Boolean = { true },
    private val onUnexpectedExit: (Boolean) -> Unit = {},
) : AutoCloseable {
    private var child: Process? = null
    private var effect: AutoCloseable? = null
    private var exclusive: AutoCloseable? = null
    private var cleanupAcknowledged = AtomicBoolean(false)
    private var routeMayBeRegistered = AtomicBoolean(false)
    private var responsesDrained = CountDownLatch(0)
    private var responses: ArrayBlockingQueue<String>? = null
    private var updateId = 0L
    @Volatile var lastFailureReason: String? = null
        private set

    @Synchronized fun start(): Boolean {
        lastFailureReason = null
        if (child != null || exclusive != null) return false
        routeMayBeRegistered.set(false)
        try {
            exclusive = acquireExclusive() ?: return failure("exclusive handoff was refused")
            val process = try { launch() } catch (error: Exception) {
                return failure("helper launch failed: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
            }
            child = process
            val ack = AtomicBoolean(false)
            val drained = CountDownLatch(1)
            cleanupAcknowledged = ack
            responsesDrained = drained
            Thread {
                if (runCatching { process.waitFor() }.isFailure) return@Thread
                drained.await(1, TimeUnit.SECONDS)
                synchronized(this) {
                    if (child === process) {
                        if (ack.get() || !routeMayBeRegistered.get()) releaseAfterExit()
                        onUnexpectedExit(child == null)
                    }
                }
            }.apply { isDaemon = true; name = "AppMixer-exit"; start() }
            val lines = ArrayBlockingQueue<String>(8)
            responses = lines
            Thread {
                try {
                    val reader = process.inputStream.bufferedReader()
                    while (true) {
                        val line = readBoundedLine(reader) ?: break
                        if (line.startsWith("APP_MIXER:RELEASED")) ack.set(true)
                        if (line == "APP_MIXER:ROUTING") routeMayBeRegistered.set(true)
                        if (!lines.offer(line)) break
                    }
                } catch (error: IOException) {
                    // Destroying a child may interrupt a blocked pipe read. It is a
                    // helper-exit condition, not an application-fatal thread failure.
                    if (process.isAlive) lines.offer("APP_MIXER:FAILED helper response pipe closed")
                } finally { lines.offer("APP_MIXER:EOF"); drained.countDown() }
            }.apply { isDaemon = true; name = "AppMixer-responses"; start() }
            Thread {
                AppMixerProcessStreamDrainer.drain(process.errorStream) { diagnostic ->
                    // Only bounded numeric relay diagnostics, never arbitrary helper stderr.
                    if (diagnostic.startsWith("Fusion Mode relay:") ||
                        diagnostic.startsWith("Fusion Mode playback buffer:")) {
                        if (diagnostic.contains("failed") || diagnostic.contains("unavailable"))
                            android.util.Log.w("LightningDSP_FusionMode", diagnostic)
                        else if (lightning.dsp.utils.logging.DiagnosticMode.isEnabled())
                            android.util.Log.i("LightningDSP_FusionMode", diagnostic)
                    }
                }
            }.apply { isDaemon = true; name = "AppMixer-errors"; start() }
            val sessionLine = awaitLine(lines, "APP_MIXER:SESSION ", 30_000)
                ?: return failure(lastFailureReason ?: "helper did not prepare local DSP and publish a session within 30 seconds")
            val session = sessionLine.removePrefix("APP_MIXER:SESSION ").toIntOrNull()
            if (session == null || session <= 0) return failure("helper published an invalid playback session")
            effect = try { prepareEffect(session) } catch (error: Exception) {
                return failure("DSP preparation threw ${error.javaClass.simpleName}: ${error.message.orEmpty().take(220)}")
            } ?: return failure("DSP preparation did not complete")
            if (!mayRoute()) return failure("mode, power, or selected-app inventory changed during startup")
            process.outputStream.write("READY\n".toByteArray(Charsets.US_ASCII))
            process.outputStream.flush()
            if (awaitLine(lines, "APP_MIXER:RUNNING ", 8_000) == null)
                return failure(lastFailureReason ?: "helper did not acknowledge its running route")
            return true
        } catch (error: Exception) {
            return failure("startup threw ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
        }
    }

    @Synchronized fun isActive(): Boolean = child?.isAlive == true
    @Synchronized fun hasOwner(): Boolean = child != null || exclusive != null

    /** Changes the registered mix without closing the shared playback path. */
    @Synchronized fun updateExclusions(exclusions: Set<Int>): Boolean = updateRules(exclusions, false)

    /** Updates usage coverage and UID exceptions atomically without stopping the relay. */
    @Synchronized fun updateRules(exclusions: Set<Int>, includeSystemSounds: Boolean): Boolean {
        val process = child ?: return false
        val lines = responses ?: return false
        if (!process.isAlive) return false
        return try {
            val values = exclusions.sorted().joinToString(",")
            AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=$values", includeSystemSounds)
            val id = ++updateId
            process.outputStream.write("UPDATE $id ${if (includeSystemSounds) 1 else 0} $values\n".toByteArray(Charsets.US_ASCII))
            process.outputStream.flush()
            if (awaitLine(lines, "APP_MIXER:UPDATED $id", 8_000) == null)
                failure(lastFailureReason ?: "mix rule update was not confirmed")
            else true
        } catch (error: Exception) {
            failure("mix rule update failed: ${error.javaClass.simpleName}")
        }
    }

    private fun failure(reason: String): Boolean {
        lastFailureReason = reason
        close()
        return false
    }

    /** The next owner is never admitted until process death is confirmed. */
    @Synchronized override fun close() {
        val process = child ?: run { releaseAfterExit(); return }
        runCatching { process.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII)); process.outputStream.flush() }
        runCatching { process.outputStream.close() }
        val exited = runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) {
            process.destroy()
            if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) return
        }
        responsesDrained.await(1, TimeUnit.SECONDS)
        if (cleanupAcknowledged.get()) releaseAfterExit()
    }

    private fun releaseAfterExit() {
        if (runCatching { effect?.close() }.isFailure) return
        effect = null
        if (runCatching { exclusive?.close() }.isFailure) return
        exclusive = null
        child = null
        responses = null
    }

    private fun awaitLine(lines: ArrayBlockingQueue<String>, prefix: String, timeoutMs: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            val line = lines.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
            if (line == "APP_MIXER:EOF") return null
            if (line.startsWith("APP_MIXER:UPDATE_FAILED ")) {
                lastFailureReason = "mix rule update was rejected or unavailable"
                return null
            }
            if (line.startsWith("APP_MIXER:FAILED ")) {
                lastFailureReason = line.removePrefix("APP_MIXER:FAILED ").take(160)
                return null
            }
            if (line.startsWith(prefix)) return line
        }
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val result = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return null
            if (c == '\n'.code) return result.toString()
            if (result.length >= 512) return null
            result.append(c.toChar())
        }
    }
}
