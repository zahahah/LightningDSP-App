package lightning.dsp.session.root

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import lightning.dsp.session.dump.data.ISessionInfoDump
import kotlinx.coroutines.Job
import lightning.dsp.MainApplication
import lightning.dsp.interop.LightningDspRemoteEngine
import lightning.dsp.interop.ProcessorMessageHandler
import lightning.dsp.model.AudioSessionDumpEntry
import lightning.dsp.model.IEffectSession
import lightning.dsp.model.root.RemoteEffectSession
import lightning.dsp.session.shared.BaseSessionDatabase
import lightning.dsp.session.shared.SESSION_DSP_ENABLED_PREFIX
import lightning.dsp.utils.extensions.ContextExtensions.getUidFromPackage
import timber.log.Timber


class RootSessionDatabase(context: Context) : BaseSessionDatabase(context) {

    /** Prevent a broadcast or enhanced scan from creating a second AudioEffect
     * for a session currently owned by the mixer-coverage controller. */
    private val supplementalSessionReservations = mutableSetOf<Int>()

    internal fun isMixerSessionLeaseHeld(): Boolean = sessionAdmission.isBlocked()

    /** Caller must stop and await supplemental retirement before acquiring. */
    @Synchronized
    internal fun acquireMixerSessionLease(): Pair<AutoCloseable, List<LightningDspRemoteEngine>>? {
        if (supplementalSessionReservations.isNotEmpty()) {
            Timber.w("App Mixer session lease refused: %d supplemental session reservation(s) remain", supplementalSessionReservations.size)
            return null
        }
        var retired = emptyList<LightningDspRemoteEngine>()
        val lease = sessionAdmission.acquire {
            retired = sessionList.values.mapNotNull { (it as? RemoteEffectSession)?.effect }
            clearSessions()
        } ?: run {
            Timber.w("App Mixer session lease refused: session admission is already held")
            return null
        }
        return lease to retired
    }

    private val sessionPreparationLeases = mutableMapOf<Int, SessionPlaybackPreparationLease>()
    private val sessionPreparationTimeouts = mutableMapOf<Int, Runnable>()
    private val playbackActivity = SessionPlaybackActivity()
    private val unifiedSessions = UnifiedSessionRetention()
    private val unifiedFocus = UnifiedSessionFocus { SystemClock.elapsedRealtime() }
    @Volatile var unifiedSessionRetentionEnabled: () -> Boolean = { false }
    @Volatile private var keepEffectsReadyEnabled = false
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var mediaPlayingPackages: Set<String> = emptySet()
    @Volatile var onProcessingStateChanged: (() -> Unit)? = null
    @Volatile var onRetentionPolicyChanged: (() -> Unit)? = null

    private val retentionLifecycle = UnifiedSessionRetentionLifecycle()
    private val retentionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val foregroundAppObserver = ForegroundAppObserver(
        context,
        onForegroundPackageChanged = { packageName ->
            retentionHandler.post { updateForegroundPackage(packageName) }
        },
    )

    private val retentionWatch = object : Runnable {
        override fun run() {
            if (retentionLifecycle.current() == null) return
            val retainEnabled = !app.isGlobalMode && unifiedSessionRetentionEnabled()
            val sessionChanged = unifiedSessions.reconcile(retainEnabled)
            val focusChanged = unifiedFocus.reconcile(retainEnabled)
            if (sessionChanged || focusChanged) {
                applyPlaybackActivity()
                onRetentionPolicyChanged?.invoke()
            }
            if (unifiedSessions.hasClaims() || unifiedFocus.hasOwners()) retentionHandler.postDelayed(this, 500L)
        }
    }

    /** Binder/death and option callbacks only enqueue reconciliation on the database's main thread. */
    fun reconcileUnifiedSessionRetention() {
        val generation = retentionLifecycle.current() ?: return
        retentionHandler.post {
            if (!retentionLifecycle.accepts(generation)) return@post
            retentionHandler.removeCallbacks(retentionWatch)
            retentionWatch.run()
        }
    }

    @Synchronized fun startUnifiedSessionRetention() { retentionLifecycle.start() }

    /** Teardown never reapplies preferences or enables effects. New provider claims are refused. */
    @Synchronized fun stopUnifiedSessionRetention() {
        retentionLifecycle.stop()
        retentionHandler.removeCallbacks(retentionWatch)
        clearSessionPreparationLeases()
        unifiedSessions.clear()
        unifiedFocus.clear()
        foregroundAppObserver.stop()
        keepEffectsReadyEnabled = false
        foregroundPackage = null
        mediaPlayingPackages = emptySet()
    }

    /** General Session Mode retention; independent of AAudio and Vector/LSPosed selection. */
    fun setKeepEffectsReadyEnabled(enabled: Boolean) {
        val effective = enabled && !app.isGlobalMode
        val changed = keepEffectsReadyEnabled != effective
        keepEffectsReadyEnabled = effective
        if (effective) foregroundAppObserver.start() else foregroundAppObserver.stop()
        retentionHandler.post {
            if (!effective) clearSessionPreparationLeases()
            if (!effective) foregroundPackage = null
            applyPlaybackActivity()
            onProcessingStateChanged?.invoke()
            if (changed) onRetentionPolicyChanged?.invoke()
        }
    }

    /**
     * Reuse and fully initialize a session already confirmed by the normal scanner before
     * AudioTrack.play(). New sessions must go through the mixer inspector, which verifies
     * that the session belongs to the requesting UID before creating an effect.
     */
    fun prepareExistingSessionBeforePlayback(
        uid: Int,
        packageName: String,
        sessionId: Int,
        deadline: Long,
        done: (Boolean) -> Unit,
    ) {
        if (sessionId <= 0 || uid % 100000 !in 10000..19999 || packageName.isBlank() ||
            deadline <= SystemClock.elapsedRealtime()) {
            done(false)
            return
        }
        retentionHandler.post {
            if (deadline <= SystemClock.elapsedRealtime() || app.isGlobalMode || !enabled ||
                !keepEffectsReadyEnabled || !isPackageDspEnabled(packageName) ||
                synchronized(this) { sessionId in supplementalSessionReservations }) {
                done(false)
                return@post
            }

            val existing = sessionList[sessionId]
            if (existing == null || existing.uid != uid || existing.packageName != packageName) {
                done(false)
                return@post
            }

            val lease = SessionPlaybackPreparationLease(uid, packageName, deadline)
            registerSessionPreparationLease(sessionId, lease)

            val session = sessionList[sessionId] as? RemoteEffectSession
            val engine = session?.effect
            if (session == null || engine == null || session.uid != uid || session.packageName != packageName) {
                removeSessionPreparationLease(sessionId, lease)
                done(false)
                return@post
            }

            engine.prepareInitialSessionState(
                shouldEnable = {
                    sessionList[sessionId] === session && enabled && isPackageDspEnabled(packageName) &&
                        (isSessionPreparationLeaseCurrent(sessionId, uid, packageName) ||
                            shouldProcess(sessionId, uid, packageName))
                },
                onComplete = { prepared ->
                    if (!prepared) expireUnstartedPreparation(sessionId, lease)
                    done(prepared)
                },
            )
        }
    }

    private fun registerSessionPreparationLease(sessionId: Int, lease: SessionPlaybackPreparationLease) {
        sessionPreparationTimeouts.remove(sessionId)?.let(retentionHandler::removeCallbacks)
        sessionPreparationLeases[sessionId] = lease
        val timeout = Runnable {
            if (sessionPreparationLeases[sessionId] !== lease) return@Runnable
            sessionPreparationLeases.remove(sessionId)
            sessionPreparationTimeouts.remove(sessionId)
            expireUnstartedPreparation(sessionId, lease)
        }
        sessionPreparationTimeouts[sessionId] = timeout
        retentionHandler.postDelayed(timeout, maxOf(0L, lease.expiresAt - SystemClock.elapsedRealtime()))
    }

    private fun isSessionPreparationLeaseCurrent(sessionId: Int, uid: Int, packageName: String): Boolean =
        sessionPreparationLeases[sessionId]?.isCurrent(sessionId, uid, packageName, SystemClock.elapsedRealtime()) == true

    private fun removeSessionPreparationLease(sessionId: Int, expected: SessionPlaybackPreparationLease? = null) {
        if (expected != null && sessionPreparationLeases[sessionId] !== expected) return
        sessionPreparationLeases.remove(sessionId)
        sessionPreparationTimeouts.remove(sessionId)?.let(retentionHandler::removeCallbacks)
    }

    private fun expireUnstartedPreparation(sessionId: Int, lease: SessionPlaybackPreparationLease) {
        if (sessionPreparationLeases[sessionId] === lease) removeSessionPreparationLease(sessionId, lease)
        val isPlaying = playbackActivity.isPlaying(sessionId, lease.uid, lease.packageName)
        if (!isPlaying && !shouldKeepUnified(sessionId, lease.uid, lease.packageName) &&
            !shouldKeepEffectsReady(sessionId, lease.packageName)) {
            removeSession(sessionId)
        } else {
            applyPlaybackActivity()
        }
    }

    private fun clearSessionPreparationLeases() {
        sessionPreparationTimeouts.values.forEach(retentionHandler::removeCallbacks)
        sessionPreparationTimeouts.clear()
        sessionPreparationLeases.clear()
    }

    /** Only actively playing media may keep background session effects ready. */
    fun setMediaPlayingPackages(packages: Set<String>) {
        val normalized = packages.filter(String::isNotBlank).toSet()
        retentionHandler.post {
            if (mediaPlayingPackages == normalized) return@post
            mediaPlayingPackages = normalized
            applyPlaybackActivity()
            onProcessingStateChanged?.invoke()
            onRetentionPolicyChanged?.invoke()
        }
    }

    private fun updateForegroundPackage(packageName: String?) {
        if (!keepEffectsReadyEnabled || foregroundPackage == packageName) return
        foregroundPackage = packageName
        applyPlaybackActivity()
        onProcessingStateChanged?.invoke()
        onRetentionPolicyChanged?.invoke()
    }

    @Synchronized fun registerUnifiedSession(id: Int, uid: Int, pid: Int, alive: () -> Boolean): Boolean =
        (retentionLifecycle.current() != null && !app.isGlobalMode && unifiedSessionRetentionEnabled() && unifiedSessions.claim(id, uid, pid, alive))
            .also { if (it) reconcileUnifiedSessionRetention() }

    @Synchronized fun registerUnifiedSessionFocus(uid: Int, pid: Int, packageName: String, focused: Boolean, alive: () -> Boolean): Boolean =
        (retentionLifecycle.current() != null && !app.isGlobalMode && unifiedSessionRetentionEnabled() &&
            unifiedFocus.update(uid, pid, packageName, focused, alive))
            .also { if (it) reconcileUnifiedSessionRetention() }
    private val playbackSuspended = mutableSetOf<Int>()
    private val confirmedInactiveRemovals = mutableSetOf<Int>()
    val needsPlaybackActivityConfirmation: Boolean get() = playbackActivity.needsConfirmation

    @Synchronized
    fun reserveSupplementalSession(id: Int): Boolean {
        if (sessionAdmission.isBlocked() || id <= 0 || sessionList.containsKey(id) || id in supplementalSessionReservations) return false
        supplementalSessionReservations += id
        return true
    }

    @Synchronized
    fun releaseSupplementalSession(id: Int) {
        supplementalSessionReservations -= id
    }

    private val app
        get() = context.applicationContext as MainApplication

    var enabled: Boolean = true
        set(value) {
            field = value
            sessionList.forEach { (id, session) ->
                val appEnabled = id <= 0 || isPackageDspEnabled(session.packageName)
                (session as RemoteEffectSession).effect?.enabled = value && appEnabled && shouldProcess(id, session.uid, session.packageName)
                recordPlaybackSuspension(id, session.uid, session.packageName)
            }
        }

    fun isPackageDspEnabled(packageName: String): Boolean = app.isGlobalMode || context
        .getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE)
        .getBoolean(SESSION_DSP_ENABLED_PREFIX + packageName, true)

    fun setPackageDspEnabled(packageName: String, enabled: Boolean) {
        context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE)
            .edit().putBoolean(SESSION_DSP_ENABLED_PREFIX + packageName, enabled).apply()
        sessionList.filterValues { it.packageName == packageName }.forEach { (id, session) ->
            if (id > 0) {
                (session as? RemoteEffectSession)?.effect?.enabled = this.enabled && enabled && shouldProcess(id, session.uid, session.packageName)
                recordPlaybackSuspension(id, session.uid, session.packageName)
            }
        }
    }

    /** A session record can outlive an audio HAL restart or a failed attachment. */
    fun hasUsableGlobalSession(): Boolean {
        val effect = (sessionList[0] as? RemoteEffectSession)?.effect?.effect ?: return false
        return runCatching { effect.enabled }.isSuccess
    }

    override fun createSession(id: Int, uid: Int, packageName: String): IEffectSession? {
        if (sessionList[id] == null) {
            Timber.d("Creating effect for session $id (${packageName})")

            val finalUid = if (uid >= 0) uid else context.getUidFromPackage(packageName)
            val effect = try {
                LightningDspRemoteEngine(context, id, 0, ProcessorMessageHandler())
            }
            catch (ex: Exception) {
                Timber.e("Failed to instantiate LightningDSP effect for session $id ($packageName)")
                Timber.e(ex.cause)

                return null
            }

            effect.enabled = enabled && (id <= 0 || isPackageDspEnabled(packageName)) &&
                shouldProcess(id, finalUid, packageName)
            recordPlaybackSuspension(id, finalUid, packageName)

            return RemoteEffectSession(packageName, finalUid, effect)
        } else {
            Timber.w("Session $id ($packageName) already existed")
            return null
        }
    }

    override fun onSessionAdded(id: Int, session: IEffectSession) {
        if (id <= 0) return
        val remote = (session as? RemoteEffectSession)?.effect ?: return
        remote.prepareInitialSessionState(shouldEnable = {
            sessionList[id] === session && this.enabled &&
                isPackageDspEnabled(session.packageName) &&
                shouldProcess(id, session.uid, session.packageName)
        })
    }

    fun syncWithPreferences() {
        sessionList.values.toList().forEach { session ->
            val engine = (session as? RemoteEffectSession)?.effect ?: return@forEach
            engine.syncWithPreferences()
            engine.enabled = enabled && (engine.sessionId <= 0 || isPackageDspEnabled(session.packageName)) &&
                shouldProcess(engine.sessionId, session.uid, session.packageName)
            recordPlaybackSuspension(engine.sessionId, session.uid, session.packageName)
        }
    }

    private fun shouldKeepUnified(id: Int, uid: Int, packageName: String): Boolean =
        !app.isGlobalMode && unifiedSessionRetentionEnabled() && unifiedFocus.isFocused(uid, packageName) &&
            unifiedSessions.shouldRetain(id, uid, packageName)

    fun shouldRetainUnifiedSupplement(id: Int, uid: Int): Boolean {
        val packageName = app.packageManager.getPackagesForUid(uid)?.singleOrNull() ?: return false
        return shouldKeepUnified(id, uid, packageName)
    }

    private fun shouldKeepEffectsReady(id: Int, packageName: String): Boolean =
        KeepEffectsReadyPolicy.shouldRetain(
            id = id,
            packageName = packageName,
            enabled = keepEffectsReadyEnabled,
            globalMode = app.isGlobalMode,
            foregroundPackage = foregroundPackage,
            mediaPlayingPackages = mediaPlayingPackages,
        )

    override fun shouldRetainMissingSession(id: Int, session: IEffectSession): Boolean =
        playbackActivity.awaitingMissingConfirmation(id, session.uid, session.packageName) ||
            isSessionPreparationLeaseCurrent(id, session.uid, session.packageName) ||
            shouldKeepUnified(id, session.uid, session.packageName) ||
            shouldKeepEffectsReady(id, session.packageName)

    private fun shouldProcess(id: Int, uid: Int, packageName: String): Boolean =
        !app.isAppMixerMode && (id <= 0 || isSessionPreparationLeaseCurrent(id, uid, packageName) ||
            shouldKeepUnified(id, uid, packageName) ||
            playbackActivity.shouldProcess(id, uid, packageName))

    /** Idle ownership stays hidden; explicitly ready-retained sessions appear as active DSP. */
    fun activeProcessingSessions(): Array<IEffectSession> = sessionList.filter { (id, session) ->
        SessionProcessingProjection.includes(
            id = id,
            processingEnabled = enabled && isPackageDspEnabled(session.packageName),
            playing = playbackActivity.isPlaying(id, session.uid, session.packageName),
            focusedRetention = false,
        )
    }.values.toTypedArray()

    fun markPlaybackUnavailable() {
        unifiedSessions.markUnavailable()
        reconcileUnifiedSessionRetention()
        playbackActivity.markUnavailable()
    }

    fun updatePlaybackActivity(dump: ISessionInfoDump) = onSessionDumpUpdated(dump)

    override fun onSessionDumpUpdated(dump: ISessionInfoDump) {
        unifiedSessions.update(dump.sessions, !app.isGlobalMode && unifiedSessionRetentionEnabled(), dump.ownerRefs)
        playbackActivity.update(dump.sessions, SystemClock.uptimeMillis(), sessionList.keys.toSet())
        sessionPreparationLeases.toMap().forEach { (id, lease) ->
            if (playbackActivity.isPlaying(id, lease.uid, lease.packageName))
                removeSessionPreparationLease(id, lease)
        }
        val inactive = playbackActivity.confirmedInactiveSessions()
        sessionList.toMap().forEach { (id, session) ->
            if (app.isEnhancedProcessing && inactive[id] == (session.uid to session.packageName) &&
                !shouldKeepUnified(id, session.uid, session.packageName) &&
                !shouldKeepEffectsReady(id, session.packageName)) {
                Timber.d("Removing inactive audio session %d (%s)", id, session.packageName)
                confirmedInactiveRemovals += id
                removeSession(id)
            }
        }
        applyPlaybackActivity()
    }

    /** Explicit lifecycle/mode reset; collector failures must not call this. */
    fun clearPlaybackActivity() {
        retentionHandler.removeCallbacks(retentionWatch)
        clearSessionPreparationLeases()
        unifiedSessions.clear()
        unifiedFocus.clear()
        playbackActivity.clear()
        mediaPlayingPackages = emptySet()
        applyPlaybackActivity()
    }

    private fun recordPlaybackSuspension(id: Int, uid: Int, packageName: String) {
        if (id > 0 && !shouldProcess(id, uid, packageName)) playbackSuspended += id
        else playbackSuspended -= id
    }

    private fun applyPlaybackActivity() {
        sessionList.toMap().forEach { (id, session) ->
            if (id <= 0) return@forEach
            val engine = (session as? RemoteEffectSession)?.effect ?: return@forEach
            val allowed = shouldProcess(id, session.uid, session.packageName)
            val desired = enabled && isPackageDspEnabled(session.packageName) && allowed
            val actual = runCatching { engine.effect?.enabled }.getOrNull()
            // Do not send redundant writes, but never cache a failed state change as applied.
            if ((id in playbackSuspended) == !allowed && actual == desired) return@forEach
            engine.enabled = desired
            if (runCatching { engine.effect?.enabled }.getOrNull() != desired) {
                Timber.w("Session %d playback processing state was not confirmed", id)
                return@forEach
            }
            recordPlaybackSuspension(id, session.uid, session.packageName)
            Timber.d("Session %d playback processing %s", id, if (allowed) "resumed" else "suspended")
        }
        onProcessingStateChanged?.invoke()
    }

    override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean {
        if (!session.isUsageRecordable()) {
            Timber.d("Skipped session $id due to usage ($session)")
            return false
        }
        return true
    }
    override fun shouldAddSession(id: Int, uid: Int, packageName: String) =
        synchronized(this) {
            !app.isAppMixerMode && id !in supplementalSessionReservations && shouldProcess(id, uid, packageName)
        }

    override fun onSessionRemoved(item: IEffectSession) {
        sessionList.entries.firstOrNull { it.value === item }?.key?.let { id ->
            removeSessionPreparationLease(id)
            playbackSuspended -= id
            if (!confirmedInactiveRemovals.remove(id)) playbackActivity.forget(id)
        }
        (item as RemoteEffectSession).run {
            item.coroutineContext[Job]!!.invokeOnCompletion {
                item.effect?.close()
            }
            item.coroutineContext[Job]?.cancel()
        }
    }

    fun addSessionByIntent(intent: Intent) {
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (sessionId < 0 || (app.isGlobalMode && sessionId > 0)) {
            return
        }
        // Global mode enabled, remove other sessions
        if (sessionId == 0)
            clearSessions()

        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"

        addSession(
            sessionId,
            context.getUidFromPackage(packageName),
            packageName,
            true
        )
    }

    /**
     * Some players announce an AudioEffect session before starting playback.
     * Admit that positive Session Mode ID without replacing the app's other
     * sessions; the AudioFlinger scanner remains authoritative for cleanup.
     */
    @Synchronized
    fun addSessionFromEarlyOpenIntent(intent: Intent): Boolean {
        if (app.isGlobalMode) return false
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (sessionId <= 0) return false
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
            ?.takeIf(String::isNotBlank) ?: return false
        val uid = context.getUidFromPackage(packageName)
        if (uid < 10_000 || uid == android.os.Process.myUid()) return false

        playbackActivity.forget(sessionId)
        val existing = sessionList[sessionId]
        if (existing != null) {
            if (existing.uid != uid || existing.packageName != packageName) {
                Timber.w("Ignoring early open for occupied session %d", sessionId)
                return false
            }
            applyPlaybackActivity()
            return true
        }

        Timber.d("Pre-registering session from early open: sid=%d; %s", sessionId, packageName)
        addSession(sessionId, uid, packageName)
        return sessionList[sessionId]?.let { it.uid == uid && it.packageName == packageName } == true
    }

    fun removeSessionByIntent(intent: Intent) {
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        if (app.isGlobalMode) {
            return
        }

        // Fix: some apps submit sid 0 sending the CLOSE broadcast
        if(packageName != null && sessionId == 0) {
            // Remove by package name instead
            sessionList.filter { it.value.packageName == packageName }
                .keys
                .forEach(::removeSession)
        }
        else {
            removeSession(sessionId)
        }
    }
}

typealias OnRootSessionChangeListener = BaseSessionDatabase.OnSessionChangeListener
