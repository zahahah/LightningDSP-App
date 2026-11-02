package lightning.dsp.interop

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

/** At most three delayed retries per external playback/device/preference change. */
internal class SupplementalInspectionRetryPolicy(private val limit: Int = 3) {
    private var retries = 0
    fun reset() { retries = 0 }
    fun retry(shouldRun: Boolean): Boolean {
        if (!shouldRun || retries >= limit) return false
        retries++
        return true
    }
}

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
    private val inspectionRetries = SupplementalInspectionRetryPolicy()
    private val diagnosticTimes = mutableMapOf<String, Long>()
    private var revision = 0L
    private var closed = false
    private var poll = false
    private var retryNotBefore = 0L
    private var lease: Long? = null
    private var barrier: CompletableDeferred<Unit>? = null
    private var leaseBaseline: LightningDspRemoteEngine? = null
    private var leaseFenced = false
    private var fenceAttempts = 0
    private var cleanupAttempts = 0
    private var diagnosticPhase = "idle"
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
                delay(maxOf(200L, retryNotBefore - SystemClock.elapsedRealtime()))
                try {
                    reconcile(revision)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Class identity is actionable; messages/stacks may contain dump
                    // fragments, package names or paths and must not be logged.
                    val type = failure.javaClass.simpleName.ifEmpty { "Exception" }
                    diagnose("controller_exception", "phase=$diagnosticPhase type=$type; baseline retained")
                    unknownSnapshot()
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
        inspectionRetries.reset()
        retryNotBefore = 0L
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
        diagnosticPhase = "baseline_check"
        val global = baseline()
        if (!shouldRun() || global == null || !global.canControlEndpoint()) {
            diagnose("baseline_unavailable", "feature disabled or controllable baseline unavailable; supplemental effects withdrawn")
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
            unknownSnapshot()
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
            diagnose("no_candidates", "no eligible supplemental candidates; self excluded=${snapshot.targets.size - targets.size}; ${snapshot.reason}")
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
                unknownSnapshot()
                return
            }
            if (!current(expected, global) || target !in before.targets) continue
            attempts.record(target.identity)
            val generation = DspDesiredState.current()
            diagnosticPhase = "construction"
            val engine = LightningDspRemoteEngine(app, target.id, 0, supplemental = true,
                onSupplementalInvalidated = ::requestInternalCheck)
            val id = engine.effectIdOrNull()
            if (id == null || !engine.canControlEndpoint() || before.effects.any { it.id == id }) {
                diagnose("creation_failed", "effect identity/control not established; supplemental discarded")
                engine.close()
                continue
            }
            owned[target.id] = Owned(target, engine, id)
            diagnosticPhase = "preparation"
            val prepared = engine.prepareSupplemental(generation)
            if (!prepared || !current(expected, global) || DspDesiredState.current() != generation) {
                diagnose("preparation_failed", "supplemental preparation/control/generation check failed; removing")
                remove(listOf(target.id))
                continue
            }
            val fresh = stableSnapshot(global)
            if (fresh == null) {
                unknownSnapshot()
                return
            }
            diagnosticPhase = "activation"
            if (!current(expected, global) || target !in fresh.targets ||
                !fresh.ownsAttachment(target, id) || !engine.activateSupplemental(generation)) {
                diagnose("activation_failed", "fresh attachment/target/control or activation check failed; removing")
                remove(listOf(target.id))
                continue
            }
            delay(300)
            val verified = stableSnapshot(global)
            if (verified == null) {
                unknownSnapshot()
                return
            }
            diagnosticPhase = "verification"
            if (!current(expected, global) || target !in verified.targets ||
                verified.effects.none { it.id == id && it.output == target.output && it.enabled && !it.suspended }) {
                diagnose("verification_failed", "enabled unsuspended attachment not verified on current target; removing")
                remove(listOf(target.id))
            } else {
                diagnose("attachment_observed", "enabled unsuspended supplemental attachment observed; audible processing unverified")
            }
        }
        if (owned.isEmpty()) releaseLeaseIfSafe()
    }

    private suspend fun ensureLeaseAndFence(global: LightningDspRemoteEngine, expected: Long): Boolean {
        diagnosticPhase = "lease_barrier"
        if (lease == null) {
            val drained = CompletableDeferred<Unit>()
            barrier = drained
            leaseBaseline = global
            leaseFenced = false
            lease = LightningDspControlClient.acquireCoverageLease { drained.complete(Unit) }
        }
        if (leaseBaseline !== global || withTimeoutOrNull(6_500) { barrier?.await(); true } != true ||
            !current(expected, global)) {
            diagnose("barrier_failed", "coverage lease baseline/barrier/current-state check failed; creation refused")
            return false
        }
        if (leaseFenced) return true
        if (fenceAttempts++ >= 3) {
            diagnose("fence_exhausted", "legacy fence retry budget exhausted; creation refused")
            return false
        }
        diagnosticPhase = "legacy_fence"
        val generation = DspDesiredState.current()
        if (!global.prepareLegacyState(generation) || !current(expected, global) || DspDesiredState.current() != generation) {
            diagnose("fence_failed", "global legacy fence did not complete; creation refused")
            return false
        }
        leaseFenced = true
        return true
    }

    private suspend fun releaseLeaseIfSafe() {
        val token = lease ?: return
        if (owned.isNotEmpty() || cleanupAttempts >= 3) return
        cleanupAttempts++
        diagnosticPhase = "lease_release"
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
            diagnose("release_failed", "native singleton safely suppressed: final barrier/fence unavailable")
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
        diagnosticPhase = "teardown"
        diagnose("owned_removed", "removing ${ids.size} owned supplemental effects; baseline retained")
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

    // Internal invalidations enqueue work without resetting the inspection budget or
    // revision. In particular retirement must not create an unbounded retry loop.
    private fun requestInternalCheck() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { requestInternalCheck() }
            return
        }
        if (!closed && poll) requests.trySend(Unit)
    }

    private suspend fun unknownSnapshot() {
        removeAll()
        releaseLeaseIfSafe()
        poll = inspectionRetries.retry(!closed && shouldRun())
        retryNotBefore = if (poll) SystemClock.elapsedRealtime() + 2_000 else 0L
        diagnose(if (poll) "inspection_retry" else "inspection_exhausted",
            if (poll) "unknown snapshot; retry after cooldown; baseline retained"
            else "unknown snapshot; retry budget exhausted or disabled; awaiting external callback")
    }

    // Fixed category keys keep this map bounded; never log dumps, exceptions,
    // package names, filesystem paths, UIDs or playback identities.
    @Synchronized
    private fun diagnose(category: String, detail: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = diagnosticTimes[category]
        if (previous != null && now - previous < 15_000) return
        diagnosticTimes[category] = now
        Timber.i("Supplemental mixer [%s]: %s", category, detail)
    }

    private suspend fun stableSnapshot(global: LightningDspRemoteEngine): GlobalMixerCoverage.Snapshot? {
        diagnosticPhase = "inspection"
        val id = global.effectIdOrNull() ?: run {
            diagnose("baseline_identity_missing", "baseline effect identity unavailable")
            return null
        }
        val first = inspect(id) ?: return null
        if (!first.known) return null
        delay(150)
        val second = inspect(id) ?: return null
        if (!second.known) return null
        if (second.signature != first.signature || global.effectIdOrNull() != id) {
            diagnose("unstable_snapshot", "topology or baseline identity changed between samples")
            return null
        }
        return second
    }

    private suspend fun inspect(globalId: Int): GlobalMixerCoverage.Snapshot? {
        val ids = owned.values.map { it.effectId }.toSet()
        return withContext(Dispatchers.IO) {
            if (!RootShellImpl.hasRootShell()) {
                diagnose("root_unavailable", "root shell unavailable; check root authorization")
                return@withContext null
            }
            val flinger = readDump("media.audio_flinger") ?: return@withContext null
            val policy = readDump("media.audio_policy") ?: return@withContext null
            GlobalMixerCoverage.inspect(flinger, policy, globalId, ids).also {
                if (!it.known) diagnose("parser_unknown", "parser rejected snapshot: ${it.reason}")
            }
        }
    }

    private fun readDump(service: String): String? {
        // Only constant service names are passed. Status sentinels contain no dump
        // contents; head bounds capture before libsu allocates output.
        val lines = RootShellImpl.cmdForOutput(
            "if [ ! -x /system/bin/timeout ]; then echo LDSP_MISSING_TIMEOUT; " +
                "elif [ ! -x /system/bin/head ]; then echo LDSP_MISSING_HEAD; else " +
                "(/system/bin/timeout 5 /system/bin/dumpsys $service && printf '\\nLDSP_MIXER_READ_COMPLETE\\n' " +
                "|| printf '\\nLDSP_MIXER_READ_FAILED\\n') " +
                "| /system/bin/head -c ${GlobalMixerCoverage.MAX_DUMP_CHARS + 1}; fi"
        )
        val status = lines.lastOrNull()
        val reason = when {
            status == "LDSP_MISSING_TIMEOUT" -> "missing_timeout"
            status == "LDSP_MISSING_HEAD" -> "missing_head"
            status == "LDSP_MIXER_READ_FAILED" -> "dump_failed_or_timed_out"
            lines.sumOf { it.length.toLong() + 1 } > GlobalMixerCoverage.MAX_DUMP_CHARS -> "dump_truncated"
            status != "LDSP_MIXER_READ_COMPLETE" -> "dump_completion_marker_missing"
            else -> null
        }
        if (reason != null) {
            diagnose(reason, "$service collection rejected ($reason); bounded read required")
            return null
        }
        return lines.dropLast(1).joinToString("\n").takeIf {
            it.length <= GlobalMixerCoverage.MAX_DUMP_CHARS
        } ?: run {
            diagnose("dump_truncated", "$service exceeds bounded diagnostic size")
            null
        }
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
