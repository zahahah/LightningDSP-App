package lightning.dsp.volumemanager.backend

import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

/** One owner with conflated state and bounded one-shot volume commands. */
internal class VolumePipeSession(
    private val process: Process,
    private val onExited: () -> Unit,
    private val onWriteFailure: (Exception) -> Unit,
    private val graceMillis: Long = 3000
) {
    enum class Phase { RUNNING, STOPPING, STOPPED }
    @Volatile var phase = Phase.RUNNING
        private set
    @Volatile var sideEffectsAuthorized = false
        private set
    fun authorizeSideEffects() { sideEffectsAuthorized = true }
    @Volatile var cleanupConfirmed = false
        private set
    fun confirmCleanup(confirmed: Boolean) { cleanupConfirmed = confirmed }
    val cleanupUncertain: Boolean get() = sideEffectsAuthorized && !cleanupConfirmed
    private val lock = Object()
    private var pendingSettings: String? = null
    private val pendingVolumeOperations = SystemVolumeOperationQueue(capacity = 32)
    private val writer: BufferedWriter = process.outputStream.bufferedWriter()

    init {
        Thread({ writeLoop() }, "LightningDSP-volume-writer").apply { isDaemon = true; start() }
        Thread({
            while (true) {
                try { process.waitFor(); break } catch (_: InterruptedException) { /* exit still unconfirmed */ }
            }
            run {
                synchronized(lock) {
                    phase = Phase.STOPPED
                    pendingSettings = null
                    pendingVolumeOperations.clear()
                    lock.notifyAll()
                }
                // Never close a pipe under the lifecycle lock: Java stream close can block.
                Thread({ runCatching { writer.close() } },
                    "LightningDSP-volume-close").apply { isDaemon = true; start() }
                onExited()
            }
        }, "LightningDSP-volume-exit").apply { isDaemon = true; start() }
    }

    fun offer(settings: String) = synchronized(lock) {
        if (phase == Phase.RUNNING) { pendingSettings = settings; lock.notifyAll() }
    }

    /** Slider targets may coalesce; key increments remain bounded FIFO operations. */
    fun offerVolumeOperation(operation: SystemVolumeOperation): Boolean = synchronized(lock) {
        if (phase != Phase.RUNNING || !pendingVolumeOperations.offer(operation)) return@synchronized false
        lock.notifyAll()
        true
    }

    fun stop() = synchronized(lock) {
        if (phase != Phase.RUNNING) return@synchronized
        phase = Phase.STOPPING
        pendingSettings = null
        pendingVolumeOperations.clear()
        lock.notifyAll()
        // Independent of a blocked writer and of the host's state executor.
        Thread({
            if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }, "LightningDSP-volume-watchdog").apply { isDaemon = true; start() }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val line = synchronized(lock) {
                    while (pendingSettings == null && pendingVolumeOperations.size == 0 && phase == Phase.RUNNING) lock.wait()
                    when {
                        phase == Phase.STOPPING -> "{\"stop\":true}"
                        phase == Phase.STOPPED -> null
                        pendingSettings != null -> pendingSettings.also { pendingSettings = null }
                        else -> pendingVolumeOperations.poll()?.toJson()?.toString()
                    }
                } ?: return
                writer.write(line); writer.newLine(); writer.flush()
                if (line == "{\"stop\":true}" || phase == Phase.STOPPED) return
            }
        } catch (failure: Exception) {
            onWriteFailure(failure)
            stop()
        }
    }
}
