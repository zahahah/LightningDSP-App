package lightning.dsp.interop

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lightning.dsp.MainApplication
import lightning.dsp.flavor.RootShellImpl
import lightning.dsp.model.root.RemoteEffectSession
import timber.log.Timber

/** Owns only supplemental session effects. Never recreates, moves or forces the baseline. */
internal class GlobalMixerFallbackController(
    context: Context,
    private val shouldRun: () -> Boolean,
) : AutoCloseable {
    private val app = context.applicationContext as MainApplication
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val owned = linkedMapOf<Int, Owned>()
    private val attempts = SupplementalAttemptBudget()
    private var revision = 0L
    private var closed = false
    private var poll = false
    private var lease: Long? = null
    private var barrier: CompletableDeferred<Unit>? = null
    private var leaseBaseline: LightningDspRemoteEngine? = null
    private var leaseFenced = false
    private var fenceAttempts = 0
    private var cleanupAttempts = 0
    private data class Owned(val session: GlobalMixerCoverage.Session, val engine: LightningDspRemoteEngine, val effectId: Int)

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = requestCheck()
    }
    private val devicesCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = requestCheck()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = requestCheck()
    }
    private val worker: Job

    init {
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        audio.registerAudioDeviceCallback(devicesCallback, handler)
        worker = scope.launch {
            while (!closed) {
                if (poll) withTimeoutOrNull(2_000) { requests.receive() } else requests.receive()
                if (closed) break
                delay(200)
                try {
                    reconcile(revision)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Timber.w(failure, "Supplemental mixer inspection unavailable; retain baseline only")
                    removeAll()
                    releaseLeaseIfSafe()
                    poll = false
                }
            }
        }
        requestCheck()
    }

    fun requestCheck() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { requestCheck() }
            return
        }
        if (closed) return
        revision++
        cleanupAttempts = 0
        fenceAttempts = 0
        if (!shouldRun()) removeAll()
        requests.trySend(Unit)
    }

    fun preferencesChanged() {
        requestCheck()
    }

    private fun baseline() = (app.rootSessionDatabase.sessionList[0] as? RemoteEffectSession)?.effect
    private fun current(expected: Long, global: LightningDspRemoteEngine) =
        !closed && revision == expected && shouldRun() && baseline() === global && global.canControlEndpoint()

    private suspend fun reconcile(expected: Long) {
        val global = baseline()
        if (!shouldRun() || global == null || !global.canControlEndpoint()) {
            removeAll()
            attempts.clear()
            releaseLeaseIfSafe()
            poll = lease != null && cleanupAttempts < 3
            return
        }
        if (lease != null && leaseBaseline !== global) {
            removeAll()
            releaseLeaseIfSafe()
            if (lease != null) return
        }
        val snapshot = stableSnapshot(global)
        if (!current(expected, global)) return
        if (snapshot == null || !snapshot.known) {
            removeAll()
            releaseLeaseIfSafe()
            poll = false
            return
        }
        poll = snapshot.activeSessions > 0
        val targets = snapshot.targets.filter { it.uid != android.os.Process.myUid() }.associateBy { it.id }
        // Withdraw before adding anything. A global chain reserves its actual output even
        // when the framework suspended it due to a positive-session effect.
        val obsolete = owned.values.filter { entry ->
            targets[entry.session.id] != entry.session || !entry.engine.canControlEndpoint() ||
                !snapshot.ownsAttachment(entry.session, entry.effectId)
        }.map { it.session.id }
        remove(obsolete)
        if (targets.isEmpty()) {
            attempts.clear()
            releaseLeaseIfSafe()
            return
        }
        attempts.retain(targets.values.map { it.identity }.toSet())
        for (target in targets.values) {
            if (!current(expected, global)) return
            if (target.id in owned || !attempts.mayAttempt(target.identity)) continue
            if (!ensureLeaseAndFence(global, expected)) {
                if (owned.isEmpty()) releaseLeaseIfSafe()
                return
            }
            if (!current(expected, global)) return
            // A native helper already accepted before the lease cannot be unsent. Both
            // the serial barrier and a successful legacy fence have completed by here.
            val before = stableSnapshot(global)
            if (before == null) {
                removeAll()
                releaseLeaseIfSafe()
                poll = false
                return
            }
            if (!current(expected, global) || target !in before.targets) continue
            attempts.record(target.identity)
            val generation = DspDesiredState.current()
            val engine = LightningDspRemoteEngine(app, target.id, 0, supplemental = true,
                onSupplementalInvalidated = ::requestCheck)
            val id = engine.effectIdOrNull()
            if (id == null || !engine.canControlEndpoint() || before.effects.any { it.id == id }) {
                engine.close()
                continue
            }
            owned[target.id] = Owned(target, engine, id)
            val prepared = engine.prepareSupplemental(generation)
            if (!prepared || !current(expected, global) || DspDesiredState.current() != generation) {
                remove(listOf(target.id))
                continue
            }
            val fresh = stableSnapshot(global)
            if (fresh == null) {
                removeAll()
                releaseLeaseIfSafe()
                poll = false
                return
            }
            if (!current(expected, global) || target !in fresh.targets ||
                !fresh.ownsAttachment(target, id) || !engine.activateSupplemental(generation)) {
                remove(listOf(target.id))
                continue
            }
            delay(300)
            val verified = stableSnapshot(global)
            if (verified == null) {
                removeAll()
                releaseLeaseIfSafe()
                poll = false
                return
            }
            if (!current(expected, global) || target !in verified.targets ||
                verified.effects.none { it.id == id && it.output == target.output && it.enabled && !it.suspended }) {
                remove(listOf(target.id))
            } else {
                Timber.i("Supplemental attachment observed: session=%s output=%s; audible processing unverified", target.id, target.output)
            }
        }
        if (owned.isEmpty()) releaseLeaseIfSafe()
    }

    private suspend fun ensureLeaseAndFence(global: LightningDspRemoteEngine, expected: Long): Boolean {
        if (lease == null) {
            val drained = CompletableDeferred<Unit>()
            barrier = drained
            leaseBaseline = global
            leaseFenced = false
            lease = LightningDspControlClient.acquireCoverageLease { drained.complete(Unit) }
        }
        if (leaseBaseline !== global || withTimeoutOrNull(6_500) { barrier?.await(); true } != true ||
            !current(expected, global)) return false
        if (leaseFenced) return true
        if (fenceAttempts++ >= 3) return false
        val generation = DspDesiredState.current()
        if (!global.prepareLegacyState(generation) || !current(expected, global) || DspDesiredState.current() != generation) {
            Timber.w("Supplemental creation refused: global legacy fence did not complete")
            return false
        }
        leaseFenced = true
        return true
    }

    private suspend fun releaseLeaseIfSafe() {
        val token = lease ?: return
        if (owned.isNotEmpty() || cleanupAttempts >= 3) return
        cleanupAttempts++
        val released = SupplementalLifecycle.releaseIfSafe(
            token = token,
            original = leaseBaseline,
            current = ::baseline,
            hasOwned = { owned.isNotEmpty() },
            awaitBarrier = { withTimeoutOrNull(6_500) { barrier?.await(); true } == true },
            generation = DspDesiredState::current,
            isClosed = LightningDspRemoteEngine::isClosed,
            fence = { endpoint, generation -> endpoint.prepareLegacyState(generation) },
            release = LightningDspControlClient::releaseCoverageLease,
        )
        if (!released) {
            Timber.w("Native singleton remains suppressed: final legacy fence unavailable")
            return
        }
        lease = null
        barrier = null
        leaseBaseline = null
        leaseFenced = false
        fenceAttempts = 0
        cleanupAttempts = 0
    }

    private fun remove(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        SupplementalLifecycle.retireOwned(ids, owned,
            hasControl = { it.engine.canControlEndpoint() },
            disable = { it.engine.enabled = false },
            close = { it.engine.close() },
        )
        // Existing engine listeners reconcile the new generation on the next
        // preference event; explicitly reapply currently owned loaded state too.
        baseline()?.syncWithPreferences()
        owned.values.forEach { it.engine.syncWithPreferences() }
    }

    private fun removeAll() = remove(owned.keys.toList())

    private suspend fun stableSnapshot(global: LightningDspRemoteEngine): GlobalMixerCoverage.Snapshot? {
        val id = global.effectIdOrNull() ?: return null
        val first = inspect(id) ?: return null
        if (!first.known) return null
        delay(150)
        val second = inspect(id) ?: return null
        return second.takeIf { it.known && it.signature == first.signature && global.effectIdOrNull() == id }
    }

    private suspend fun inspect(globalId: Int): GlobalMixerCoverage.Snapshot? {
        val ids = owned.values.map { it.effectId }.toSet()
        return withContext(Dispatchers.IO) {
            if (!RootShellImpl.hasRootShell()) return@withContext null
            val flinger = readDump("media.audio_flinger") ?: return@withContext null
            val policy = readDump("media.audio_policy") ?: return@withContext null
            GlobalMixerCoverage.inspect(flinger, policy, globalId, ids)
        }
    }

    private fun readDump(service: String): String? {
        // Only constant service names are passed here. Limit output before libsu
        // captures it; a missing marker rejects failures, timeouts and truncation.
        val lines = RootShellImpl.cmdForOutput(
            "[ -x /system/bin/timeout ] && ([ -x /system/bin/head ] && " +
                "(/system/bin/timeout 5 /system/bin/dumpsys $service && printf '\\nLDSP_MIXER_READ_COMPLETE\\n') " +
                "| /system/bin/head -c ${GlobalMixerCoverage.MAX_DUMP_CHARS + 1})"
        )
        if (lines.lastOrNull() != "LDSP_MIXER_READ_COMPLETE") return null
        return lines.dropLast(1).joinToString("\n").takeIf { it.length <= GlobalMixerCoverage.MAX_DUMP_CHARS }
    }

    override fun close() {
        if (closed) return
        closed = true
        revision++
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        audio.unregisterAudioDeviceCallback(devicesCallback)
        removeAll()
        worker.cancel()
        scope.launch {
            worker.join()
            repeat(3) {
                releaseLeaseIfSafe()
                if (lease == null) return@repeat
                // Service teardown may close the original endpoint immediately after
                // this method returns. Observe that before retaining a safe block.
                delay(300)
            }
            if (lease != null) Timber.w("Coverage owner closed with native safely held; recovery requires successful fence or process restart")
            requests.close()
            scope.cancel()
        }
    }
}
