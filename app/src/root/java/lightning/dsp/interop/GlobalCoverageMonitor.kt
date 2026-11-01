package lightning.dsp.interop

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lightning.dsp.flavor.RootShellImpl
import timber.log.Timber

/** Inspects coverage, not engine health. Root reads and retries are bounded; no audio services are restarted. */
internal class GlobalCoverageMonitor(
    context: Context,
    private val shouldMonitor: () -> Boolean,
    private val reconcile: () -> Boolean,
) : AutoCloseable {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = MainScope()
    private val inspection = Mutex()
    private var pending: Job? = null
    private var debounce: Job? = null
    private var rerunRequested = false
    private var requestRevision = 0L
    private var closed = false
    private var attempted = false
    private var attemptedAt = 0L
    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = requestCheck()
    }

    init {
        audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun requestCheck() {
        if (closed) return
        requestRevision++
        debounce?.cancel()
        if (!shouldMonitor()) {
            rerunRequested = false
            pending?.cancel()
            return
        }
        debounce = scope.launch {
            delay(350)
            if (pending?.isActive == true) {
                // Playback callbacks caused by reattachment must not cancel its verification.
                rerunRequested = true
            } else {
                launchInspection()
            }
        }
    }

    private fun launchInspection() {
        pending = scope.launch {
          try {
            inspection.withLock {
                if (!shouldMonitor() || closed) return@withLock
                val generation = DspDesiredState.current()
                val first = inspect() ?: return@withLock
                if (!isCurrent(generation)) return@withLock
                if (first.state == GlobalOutputCoverage.State.IDLE) {
                    // A reattachment can briefly look idle. It must not rearm its own recovery.
                    if (attempted) {
                        val idleRevision = requestRevision
                        val remainingCooldown = (5_000 - (SystemClock.elapsedRealtime() - attemptedAt)).coerceAtLeast(0)
                        delay(remainingCooldown + 2_000)
                        if (requestRevision == idleRevision && isCurrent(generation) &&
                            inspect()?.state == GlobalOutputCoverage.State.IDLE) attempted = false
                    }
                    return@withLock
                }
                if (first.state != GlobalOutputCoverage.State.MISMATCH) {
                    if (first.state != GlobalOutputCoverage.State.COVERED)
                        Timber.w("Global attachment coverage not established: %s", first.reason)
                    return@withLock
                }
                if (attempted) return@withLock
                delay(250)
                val confirmed = inspect() ?: return@withLock
                if (!isCurrent(generation) || confirmed.state != GlobalOutputCoverage.State.MISMATCH ||
                    confirmed.signature != first.signature) return@withLock
                if (!nativeReady()) {
                    Timber.w("Global attachment mismatch cannot be reconciled: ldsp.control context is not ready and unambiguous")
                    return@withLock
                }
                if (!isCurrent(generation)) return@withLock
                attempted = true
                attemptedAt = SystemClock.elapsedRealtime()
                if (!reconcile()) {
                    Timber.w("Global attachment reconciliation refused; no retry in this playback episode")
                    return@withLock
                }
                val reconciledGeneration = DspDesiredState.current()
                delay(750)
                if (!isCurrent(reconciledGeneration)) return@withLock
                val after = inspect()
                val native = nativeReady()
                if (!isCurrent(reconciledGeneration)) return@withLock
                if (after?.state == GlobalOutputCoverage.State.COVERED && native) {
                    Timber.i("Global attachment verified on output %s; ldsp.control writable context verified (not an audibility test)", after.activeOutput)
                } else {
                    Timber.w("Global attachment coverage remains unverified after one attempt: %s; nativeReady=%s", after?.reason ?: "inspection unavailable", native)
                }
            }
          } finally {
            if (rerunRequested && !closed && shouldMonitor()) {
                rerunRequested = false
                requestCheck()
            }
          }
        }
    }

    private fun isCurrent(generation: Long) = !closed && shouldMonitor() && DspDesiredState.current() == generation

    private suspend fun inspect(): GlobalOutputCoverage.Snapshot? = withContext(Dispatchers.IO) {
        try {
            if (!RootShellImpl.hasRootShell()) return@withContext null
            val flinger = readBounded("/system/bin/dumpsys media.audio_flinger") ?: return@withContext null
            val policy = readBounded("/system/bin/dumpsys media.audio_policy") ?: return@withContext null
            GlobalOutputCoverage.inspect(flinger, policy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.w(failure, "Global attachment inspection unavailable")
            null
        }
    }

    private suspend fun nativeReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!RootShellImpl.hasRootShell()) return@withContext false
            readBounded("/vendor/bin/ldspctl probe")?.lineSequence()?.mapNotNull { NativeControlReply.parse(it) }?.lastOrNull()?.ready == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.w(failure, "Global attachment native context verification unavailable")
            false
        }
    }

    private fun readBounded(command: String): String? {
        // Never fall back to an unbounded dump if the firmware lacks timeout.
        val result = RootShellImpl.cmdForOutput(
            "[ -x /system/bin/timeout ] && /system/bin/timeout 5 $command && printf '\\nLDSP_COVERAGE_READ_COMPLETE\\n'"
        )
        if (result.lastOrNull() != "LDSP_COVERAGE_READ_COMPLETE") {
            Timber.w("Global attachment inspection incomplete or timed out; coverage is unknown")
            return null
        }
        return result.dropLast(1).joinToString("\n").takeIf { it.length <= 2_000_000 }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending?.cancel()
        debounce?.cancel()
        rerunRequested = false
        audioManager.unregisterAudioPlaybackCallback(callback)
        scope.cancel()
    }
}
