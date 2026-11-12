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
    private companion object {
        // Playback/device callbacks trigger prompt reconciliation. This is only a
        // lost-callback safety check, and each pass reads two full system dumps twice.
        const val ACTIVE_RECONCILE_INTERVAL_MS = 10_000L
        const val SESSION_PREPLAY_IDLE_GRACE_MS = 1_500L
    }

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
    private var excludedUids: Set<Int> = emptySet()
    private var closed = false
    private val closedAndDrained = CompletableDeferred<Boolean>()
    private var poll = false
    private var retryNotBefore = 0L
    private var lease: Long? = null
    private var barrier: CompletableDeferred<Unit>? = null
    private var leaseBaseline: LightningDspRemoteEngine? = null
    private var leaseFenced = false
    private var fenceAttempts = 0
    private var cleanupAttempts = 0
    private var diagnosticPhase = "idle"
    private data class Owned(
        val session: GlobalMixerCoverage.Session,
        val engine: LightningDspRemoteEngine,
        val effectId: Int,
        val reservedInSessionDatabase: Boolean,
    )

    private fun isAppDspEnabled(uid: Int): Boolean {
        if (app.isGlobalMode) return true
        val packageName = app.packageManager.getPackagesForUid(uid)?.singleOrNull() ?: return true
        return app.rootSessionDatabase.isPackageDspEnabled(packageName)
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = requestCheck()
    }
    private val devicesCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = requestCheck()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = requestCheck()
    }
    private data class PreparationWaiter(val deadline: Long, val done: (Boolean) -> Unit)
    private class Preparation(
        val uid: Int,
        var expires: Long,
        val sessionPlayback: Boolean,
        done: (Boolean) -> Unit,
        deadline: Long,
    ) {
        val waiters = mutableListOf(PreparationWaiter(deadline, done))
        var timeout: Runnable? = null
    }
    private val preparations = mutableMapOf<Int, Preparation>()
    private val neverStarted = mutableMapOf<Int, Long>()
    private val preparationHandler: (Int, Int, Long, Boolean, (Boolean) -> Unit) -> Unit = { uid, id, deadline, sessionPlayback, done ->
        handler.post {
            val requestEnabled = if (sessionPlayback)
                !app.isGlobalMode && KeepEffectsReadyOption.requested(app)
            else AAudioCompatibilityOption.requested(app)
            val now = SystemClock.elapsedRealtime()
            val existing = preparations[id]
            if (deadline <= now || closed || !shouldRun() || !requestEnabled) done(false)
            else if (existing != null) {
                // One Android session can be started by several tracks. Coalesce duplicate
                // requests rather than rejecting every later session from the same app.
                if (existing.uid == uid && existing.sessionPlayback == sessionPlayback && existing.expires > now) {
                    existing.waiters += PreparationWaiter(deadline, done)
                    existing.expires = maxOf(existing.expires, deadline)
                    schedulePreparationTimeout(id, existing)
                } else done(false)
            } else if (preparations.size >= 2) done(false)
            else {
                val pending = Preparation(uid, deadline, sessionPlayback, done, deadline)
                preparations[id] = pending
                schedulePreparationTimeout(id, pending)
                requests.trySend(Unit)
            }
        }
    }

    private fun schedulePreparationTimeout(id: Int, pending: Preparation) {
        pending.timeout?.let(handler::removeCallbacks)
        val timeout = Runnable {
            if (preparations[id] === pending) finishPreparation(id, pending, false)
        }
        pending.timeout = timeout
        handler.postDelayed(timeout, maxOf(0L, pending.expires - SystemClock.elapsedRealtime()))
    }

    private fun finishPreparation(id: Int, pending: Preparation, prepared: Boolean) {
        if (preparations[id] !== pending) return
        preparations.remove(id)
        pending.timeout?.let(handler::removeCallbacks)
        val now = SystemClock.elapsedRealtime()
        pending.waiters.forEach { waiter ->
            runCatching { waiter.done(prepared && waiter.deadline > now) }
        }
    }
    private val worker: Job

    init {
        CompatibilityPreparationBridge.handler = preparationHandler
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        audio.registerAudioDeviceCallback(devicesCallback, handler)
        worker = scope.launch {
            while (!closed) {
                if (poll) withTimeoutOrNull(ACTIVE_RECONCILE_INTERVAL_MS) { requests.receive() } else requests.receive()
                if (closed) break
                delay(maxOf(75L, retryNotBefore - SystemClock.elapsedRealtime()))
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

    fun setExcludedUids(uids: Set<Int>) {
        excludedUids = uids
        requestCheck()
    }

    fun hasOwnedSessions(): Boolean = owned.isNotEmpty()

    private fun admissionCurrent(target: GlobalMixerCoverage.Session, snapshot: GlobalMixerCoverage.Snapshot): Boolean {
        val pending = preparations[target.id] ?: return target.id in snapshot.playingSessionIds
        if (pending.uid != target.uid) return false
        return target.id in snapshot.playingSessionIds ||
            (CompatibilityPreparationAdmission.current(target.uid, pending.uid, pending.expires, SystemClock.elapsedRealtime()) &&
                (!pending.sessionPlayback || (!app.isGlobalMode && KeepEffectsReadyOption.requested(app))))
    }

    private fun baseline() = (app.rootSessionDatabase.sessionList[0] as? RemoteEffectSession)?.effect
    private fun current(expected: Long, global: LightningDspRemoteEngine?) =
        !closed && revision == expected && shouldRun() && baseline() === global &&
            (global?.canControlEndpoint() ?: !app.isGlobalMode)

    private suspend fun reconcile(expected: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        diagnosticPhase = "baseline_check"
        val global = baseline()
        if (!shouldRun() || (app.isGlobalMode && (global == null || !global.canControlEndpoint())) ||
            (global != null && !global.canControlEndpoint())) {
            diagnose("baseline_unavailable", "feature disabled or required control endpoint unavailable; supplemental effects withdrawn")
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
        val inspectedPreparations = preparations.toMap()
        val snapshot = stableSnapshot(global, retainOnlyOnTransition = true)
        if (!current(expected, global)) return
        if (snapshot == null || !snapshot.known) {
            unknownSnapshot()
            return
        }
        neverStarted.keys.removeAll(snapshot.playingSessionIds)
        val expiredIdle = neverStarted.filterValues { it <= SystemClock.elapsedRealtime() }.keys.toList()
        remove(expiredIdle.filter { id ->
            val session = owned[id]?.session ?: return@filter true
            app.isGlobalMode || !app.rootSessionDatabase.shouldRetainUnifiedSupplement(id, session.uid)
        })
        expiredIdle.forEach(neverStarted::remove)
        inspectedPreparations.forEach { (id, pending) ->
            if (preparations[id] === pending && !snapshot.reason.startsWith("Topology changing") && snapshot.targets.none { it.id == id && it.uid == pending.uid } && id !in owned) {
                finishPreparation(id, pending, false)
            }
        }
        poll = snapshot.activeSessions > 0 || owned.isNotEmpty()
        val targets = snapshot.targets.filter {
            it.uid != android.os.Process.myUid() && it.uid !in excludedUids && isAppDspEnabled(it.uid)
        }.associateBy { it.id }
        val disabledOwned = owned.filterValues { !isAppDspEnabled(it.session.uid) }.keys
        remove(disabledOwned)
        // Withdraw before adding anything. A global chain reserves its actual output even
        // when the framework suspended it due to a positive-session effect.
        val retainInactiveSessionIds = if (app.isGlobalMode) {
            snapshot.retainableSessions.mapTo(mutableSetOf()) { it.id }
        } else {
            snapshot.retainableSessions.filter {
                app.rootSessionDatabase.shouldRetainUnifiedSupplement(it.id, it.uid)
            }.mapTo(mutableSetOf()) { it.id }
        } + preparations.filter { (id, request) -> request.expires > SystemClock.elapsedRealtime() &&
            snapshot.retainableSessions.any { it.id == id && it.uid == request.uid } }.keys
        val plan = GlobalMixerCoverage.reconcile(snapshot, owned.values.map {
            GlobalMixerCoverage.OwnedAttachment(it.session, it.effectId,
                it.engine.canControlEndpoint() && it.session.uid !in excludedUids)
        }, excludedUid = android.os.Process.myUid(), retainInactiveSessionIds = retainInactiveSessionIds).let { coverage ->
            coverage.copy(newTargets = coverage.newTargets.filter {
                it.uid !in excludedUids && isAppDspEnabled(it.uid)
            })
        }
        remove(plan.obsolete)
        if (targets.isEmpty()) {
            if (snapshot.reason.startsWith("Topology changing") && preparations.isNotEmpty()) {
                handler.postDelayed({
                    if (!closed && preparations.values.any { it.expires > SystemClock.elapsedRealtime() }) requests.trySend(Unit)
                }, 75)
            }
            diagnose("no_candidates", "no eligible supplemental candidates; self excluded=${snapshot.targets.size - targets.size}; ${snapshot.reason}")
            attempts.clear()
            releaseLeaseIfSafe()
            return
        }
        attempts.retain(targets.values.map { it.identity }.toSet())
        for (target in plan.newTargets) {
            if (!current(expected, global)) return
            if (target.id in owned || !attempts.mayAttempt(target.identity)) continue
            if (owned.size >= GlobalMixerCoverage.MAX_SUPPLEMENTAL_SESSIONS) break
            if (!ensureLeaseAndFence(global, expected)) {
                if (owned.isEmpty()) releaseLeaseIfSafe()
                return
            }
            if (!current(expected, global)) return
            // A native helper already accepted before the lease cannot be unsent. Both
            // the serial barrier and a successful legacy fence have completed by here.
            val before = inspect(global?.effectIdOrNull())
            if (before == null || !before.known) {
                unknownSnapshot()
                return
            }
            if (!current(expected, global) || target !in before.targets || !admissionCurrent(target, before)) continue
            attempts.record(target.identity)
            val generation = DspDesiredState.current()
            diagnosticPhase = "construction"
            val reserved = !app.isGlobalMode && app.rootSessionDatabase.reserveSupplementalSession(target.id)
            if (!app.isGlobalMode && !reserved) continue
            if (!current(expected, global) || app.rootSessionDatabase.sessionList.containsKey(target.id)) {
                if (reserved) app.rootSessionDatabase.releaseSupplementalSession(target.id)
                continue
            }
            val engine = LightningDspRemoteEngine(app, target.id, 0, supplemental = true,
                onSupplementalInvalidated = ::requestInternalCheck)
            val id = engine.effectIdOrNull()
            if (id == null || !engine.canControlEndpoint() || before.effects.any { it.id == id }) {
                diagnose("creation_failed", "effect identity/control not established; supplemental discarded")
                engine.close()
                if (reserved) app.rootSessionDatabase.releaseSupplementalSession(target.id)
                continue
            }
            owned[target.id] = Owned(target, engine, id, reserved)
            preparations[target.id]?.let { request ->
                val grace = if (request.sessionPlayback) SESSION_PREPLAY_IDLE_GRACE_MS else 10_000L
                neverStarted[target.id] = SystemClock.elapsedRealtime() + grace
                handler.postDelayed({
                    if (!closed && neverStarted[target.id]?.let { it <= SystemClock.elapsedRealtime() } == true)
                        requests.trySend(Unit)
                }, grace)
            }
            diagnosticPhase = "preparation"
            val prepared = engine.prepareSupplemental(generation)
            if (!prepared || !current(expected, global) || !admissionCurrent(target, before) || DspDesiredState.current() != generation) {
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
            if (!current(expected, global) || target !in fresh.targets || !admissionCurrent(target, fresh) ||
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
                preparations[target.id]?.let { request ->
                    finishPreparation(target.id, request,
                        request.uid == target.uid && request.expires > SystemClock.elapsedRealtime())
                }
                diagnose("attachment_observed", "enabled unsuspended supplemental attachment observed in ${SystemClock.elapsedRealtime() - startedAt} ms; audible processing unverified")
            }
        }
        if (owned.isEmpty()) releaseLeaseIfSafe()
    }

    private suspend fun ensureLeaseAndFence(global: LightningDspRemoteEngine?, expected: Long): Boolean {
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
        if (global == null) {
            if (app.isGlobalMode || !current(expected, null)) {
                diagnose("fence_failed", "session-only baseline changed; creation refused")
                return false
            }
            // The worker barrier drained earlier unscoped writes. With no session-0
            // AudioEffect in Session Mode, there is no live endpoint to fence.
            app.rootSessionDatabase.syncWithPreferences()
            leaseFenced = true
            return true
        }
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
        if (!app.isGlobalMode && baseline() == null) {
            // Lease release invalidates stale generations. Reconcile every ordinary
            // Session Mode engine instead of leaving existing sessions on old work.
            app.rootSessionDatabase.syncWithPreferences()
        }
    }

    private fun remove(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        diagnosticPhase = "teardown"
        diagnose("owned_removed", "removing ${ids.size} owned supplemental effects; baseline retained")
        ids.forEach(neverStarted::remove)
        val reservedIds = ids.filter { owned[it]?.reservedInSessionDatabase == true }
        SupplementalLifecycle.retireOwned(ids, owned,
            hasControl = { it.engine.canControlEndpoint() },
            disable = { it.engine.enabled = false },
            close = { it.engine.close() },
        )
        reservedIds.forEach(app.rootSessionDatabase::releaseSupplementalSession)
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

    private suspend fun stableSnapshot(global: LightningDspRemoteEngine?, retainOnlyOnTransition: Boolean = false): GlobalMixerCoverage.Snapshot? {
        diagnosticPhase = "inspection"
        val id = global?.effectIdOrNull()
        if (global != null && id == null) {
            diagnose("baseline_identity_missing", "baseline effect identity unavailable")
            return null
        }
        val pendingUids = preparations.filterValues { it.expires > SystemClock.elapsedRealtime() }
            .mapValues { it.value.uid }
        val first = inspect(id, pendingUids) ?: return null
        if (!first.known) return null
        delay(150)
        val second = inspect(id, pendingUids) ?: return null
        if (!second.known) return null
        if (global?.effectIdOrNull() != id) return null
        if (second.signature != first.signature) {
            diagnose("unstable_snapshot", "topology changed between samples")
            return if (retainOnlyOnTransition)
                GlobalMixerCoverage.retentionOnlyDuringTransition(first, second) else null
        }
        return second
    }

    private suspend fun inspect(globalId: Int?, pendingUids: Map<Int, Int> =
        preparations.filterValues { it.expires > SystemClock.elapsedRealtime() }.mapValues { it.value.uid }
    ): GlobalMixerCoverage.Snapshot? {
        val ids = owned.values.map { it.effectId }.toSet()
        return withContext(Dispatchers.IO) {
            if (!RootShellImpl.hasRootShell()) {
                diagnose("root_unavailable", "root shell unavailable; check root authorization")
                return@withContext null
            }
            val flinger = readDump("media.audio_flinger") ?: return@withContext null
            val policy = readDump("media.audio_policy") ?: return@withContext null
            GlobalMixerCoverage.inspect(flinger, policy, globalId, ids,
                pendingUids).also {
                if (!it.known) diagnose("parser_unknown", "parser rejected snapshot: ${it.reason}")
            }
        }.also { snapshot ->
            if (snapshot?.known == true) neverStarted.keys.removeAll(snapshot.playingSessionIds)
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

    /** A successful result is required before another engine owner is admitted. */
    suspend fun awaitClosed(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { closedAndDrained.await() } == true

    override fun close() {
        if (CompatibilityPreparationBridge.handler === preparationHandler) CompatibilityPreparationBridge.handler = null
        preparations.toMap().forEach { (id, pending) -> finishPreparation(id, pending, false) }
        preparations.clear()
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
            closedAndDrained.complete(lease == null && owned.isEmpty())
            requests.close()
            scope.cancel()
        }
    }
}
