package lightning.dsp.interop

import android.os.Looper
import lightning.dsp.flavor.RootShellImpl
import timber.log.Timber
import java.util.concurrent.Executors

/** Context identity is independent of a probe result: an old probe cannot revive readiness. */
internal class NativeControlContextState {
    private var epoch = 0L
    private var probeEpoch: Long? = null
    private var available = false
    private val callbacks = mutableListOf<(Boolean) -> Unit>()
    data class Request(val epoch: Long, val launch: Boolean, val cached: Boolean)
    val ready: Boolean get() = synchronized(this) { available }
    @Synchronized fun currentEpoch() = epoch
    @Synchronized fun isCurrent(expected: Long) = epoch == expected
    @Synchronized fun invalidate() {
        epoch++
        available = false
        probeEpoch = null
        callbacks.clear()
    }
    @Synchronized fun request(callback: ((Boolean) -> Unit)?): Request {
        if (available) return Request(epoch, false, true)
        callback?.let(callbacks::add)
        val launch = probeEpoch == null
        if (launch) probeEpoch = epoch
        return Request(epoch, launch, false)
    }
    @Synchronized fun record(expected: Long, value: Boolean): Boolean {
        if (epoch != expected) return false
        available = value
        return true
    }
    @Synchronized fun finish(expected: Long): List<(Boolean) -> Unit> {
        if (epoch != expected) return emptyList()
        probeEpoch = null
        return callbacks.toList().also { callbacks.clear() }
    }
}

/** Global ldsp.control client. UID 0 and the vendor-native binding stay in the
 * module helper; the ordinary app never uses guessed Binder codes or Java AIDL
 * to disguise the native service's vendor-local stability. */
internal object LightningDspControlClient {
    private const val HELPER = "/vendor/bin/ldspctl"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ldsp-global-control").apply { isDaemon = true }
    }
    @Volatile private var rootAvailable = false
    private val contextState = NativeControlContextState()
    private val coverageGate = NativeControlCoverageGate(
        enqueue = { executor.execute(it) },
        invalidate = { DspDesiredState.replace { contextState.invalidate() } },
    )
    private val ready: Boolean get() = !coverageGate.blocked && contextState.ready
    private val writes = NativeControlWriteDispatcher(
        isReady = { ready },
        currentGeneration = DspDesiredState::current,
        enqueue = { executor.execute(it) },
        execute = { action, args ->
            runHelper(action, *args.toTypedArray()).also { response ->
                Timber.i("ldsp.control: $action accepted=${response?.accepted == true} applied=${response?.applied == true}; ${response?.raw ?: "transport unavailable"}")
            }
        },
    )

    fun isAvailable() = ready
    fun isCoverageLeaseActive() = coverageGate.blocked
    fun needsRootPermission() = !rootAvailable

    /** Call before releasing/replacing the AudioEffect; probeAsync must run after creation. */
    fun invalidateContext() = contextState.invalidate()

    /** Quiescence is not a native-parameter rollback. After this barrier the
     * caller must successfully write the current global legacy state before
     * constructing any supplemental AudioEffect. */
    fun acquireCoverageLease(onQuiesced: (Long) -> Unit): Long {
        return coverageGate.acquire(onQuiesced)
    }

    /** Caller has already closed its extras and completed the final legacy
     * fence, or established that its original endpoint was destroyed. */
    fun releaseCoverageLease(token: Long) {
        if (coverageGate.release(token)) probeAsync()
    }

    fun probeAsync(onComplete: ((Boolean) -> Unit)? = null) {
        if (coverageGate.blocked) {
            onComplete?.let { callback -> executor.execute { callback(false) } }
            return
        }
        val request = contextState.request(onComplete)
        if (request.cached) {
            onComplete?.let { callback ->
                executor.execute {
                    if (contextState.isCurrent(request.epoch) && ready) callback(true)
                }
            }
            return
        }
        if (!request.launch) return
        executor.execute {
            var result = false
            try {
                if (coverageGate.blocked || !contextState.isCurrent(request.epoch)) return@execute
                rootAvailable = RootShellImpl.hasRootShell()
                if (!rootAvailable) {
                    Timber.i("ldsp.control: root unavailable; legacy fallback")
                } else {
                    for (delayMs in longArrayOf(0, 250, 500, 1_000, 2_000)) {
                        if (coverageGate.blocked || !contextState.isCurrent(request.epoch)) break
                        if (delayMs > 0) Thread.sleep(delayMs)
                        if (coverageGate.blocked || !contextState.isCurrent(request.epoch)) break
                        val response = runHelper("probe")
                        if (!contextState.record(request.epoch, response?.ready == true)) break
                        if (ready) {
                            Timber.i("ldsp.control: global native backend ready; ${response?.raw}")
                            break
                        }
                    }
                }
                result = contextState.isCurrent(request.epoch) && ready
                if (!result) Timber.i("ldsp.control: discovery/authorization/context gate unavailable; legacy fallback")
            } catch (e: Exception) {
                Timber.w(e, "ldsp.control: probe failed; legacy fallback")
            } finally {
                val callbacks = contextState.finish(request.epoch)
                callbacks.forEach { callback ->
                    if (contextState.isCurrent(request.epoch)) {
                        try { callback(result && ready) }
                        catch (e: Exception) { Timber.w(e, "ldsp.control: availability callback failed") }
                    }
                }
            }
        }
    }

    fun setEnabledIfSupported(enabled: Boolean): Boolean = write("enabled", listOf(enabled.toString()))

    fun setPostGainIfSupported(gainDb: Float, fallback: () -> Unit = {}): Boolean =
        PostGainRangePolicy.accepts(gainDb, true) && write("post-gain", listOf(gainDb.toString()), fallback)

    fun setFirEqIfSupported(frequenciesHz: FloatArray, gainsDb: FloatArray,
                            interpolation: Int, enabled: Boolean, fallback: () -> Unit = {}): Boolean {
        if (frequenciesHz.size != 15 || gainsDb.size != 15 || interpolation !in 0..1 ||
            frequenciesHz.any { !it.isFinite() || it <= 0f } ||
            gainsDb.any { !it.isFinite() || it !in -64f..64f }) return false
        return write("fir", listOf(enabled.toString(), interpolation.toString(),
            frequenciesHz.joinToString(","), gainsDb.joinToString(",")), fallback)
    }

    private fun write(action: String, args: List<String>, fallback: () -> Unit = {}): Boolean {
        // Always serialize on the native worker, including preference changes
        // received on Android's main thread. The caller must not also write the
        // same legacy parameter merely because Binder has not replied yet.
        val generation = DspDesiredState.current()
        val queued = writes.dispatch(action, args) {
            DspDesiredState.applyIfCurrent(generation, fallback)
        }
        if (!queued) probeAsync()
        return queued
    }

    private fun runHelper(action: String, vararg args: String): NativeControlReply? {
        if (coverageGate.blocked || !rootAvailable || Looper.myLooper() == Looper.getMainLooper()) return null
        val epoch = contextState.currentEpoch()
        return try {
            val command = (listOf(HELPER, action) + args).joinToString(" ", transform = ::shellQuote)
            val reply = RootShellImpl.cmdForOutput(command).asReversed()
                ?.firstNotNullOfOrNull { NativeControlReply.parse(it) }
                ?.also {
                    if (!it.ok) {
                        Timber.w("ldsp.control: ${it.raw}")
                        if (it.raw.contains("service-unavailable") || it.raw.contains("-transport")) {
                            if (contextState.record(epoch, false)) probeAsync()
                        }
                    }
                }
            if (reply == null && contextState.record(epoch, false)) probeAsync()
            reply
        } catch (e: Exception) {
            Timber.w(e, "ldsp.control: native helper failed; legacy fallback")
            null
        }
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
