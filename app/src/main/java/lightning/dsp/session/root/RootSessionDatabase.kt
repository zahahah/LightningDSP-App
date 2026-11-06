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
import lightning.dsp.utils.extensions.ContextExtensions.toast
import timber.log.Timber


class RootSessionDatabase(context: Context) : BaseSessionDatabase(context) {

    /** Prevent a broadcast or enhanced scan from creating a second AudioEffect
     * for a session currently owned by the mixer-coverage controller. */
    private val supplementalSessionReservations = mutableSetOf<Int>()
    private val playbackActivity = SessionPlaybackActivity()
    private val playbackSuspended = mutableSetOf<Int>()
    val needsPlaybackActivityConfirmation: Boolean get() = playbackActivity.needsConfirmation

    @Synchronized
    fun reserveSupplementalSession(id: Int): Boolean {
        if (id <= 0 || sessionList.containsKey(id) || id in supplementalSessionReservations) return false
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

                // Debug toast
                context.toast("LDSP load fail (session=$id): " + ex.message)
                return null
            }

            effect.syncWithPreferences()
            effect.enabled = enabled && (id <= 0 || isPackageDspEnabled(packageName)) &&
                shouldProcess(id, finalUid, packageName)
            recordPlaybackSuspension(id, finalUid, packageName)

            return RemoteEffectSession(packageName, finalUid, effect)
        } else {
            Timber.w("Session $id ($packageName) already existed")
            return null
        }
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

    private fun shouldProcess(id: Int, uid: Int, packageName: String): Boolean =
        id <= 0 || !app.isEnhancedProcessing || playbackActivity.shouldProcess(id, uid, packageName)

    override fun onSessionDumpUpdated(dump: ISessionInfoDump) {
        playbackActivity.update(dump.sessions, SystemClock.uptimeMillis())
        applyPlaybackActivity()
    }

    /** Unknown/failed observations must not strand a resumed session in bypass. */
    fun clearPlaybackActivity() {
        playbackActivity.clear()
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
    }

    override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean {
        if (!session.isUsageRecordable()) {
            Timber.d("Skipped session $id due to usage ($session)")
            return false
        }
        return true
    }
    override fun shouldAddSession(id: Int, uid: Int, packageName: String) =
        synchronized(this) { id !in supplementalSessionReservations }

    override fun onSessionRemoved(item: IEffectSession) {
        sessionList.entries.firstOrNull { it.value === item }?.key?.let { id ->
            playbackSuspended -= id
            playbackActivity.forget(id)
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
        playbackActivity.forget(sessionId)
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
