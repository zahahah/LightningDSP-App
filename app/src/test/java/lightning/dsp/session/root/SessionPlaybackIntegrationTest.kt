package lightning.dsp.session.root

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Wiring contracts complement pure parser/state tests; Android timing requires device validation. */
class SessionPlaybackIntegrationTest {
    private fun source(path: String) = sequenceOf(File("."), File("app"))
        .map { File(it, "src/main/java/lightning/dsp/$path.kt") }.first { it.isFile }.readText()

    @Test fun resumeInvalidatesOldObservationsAndRefreshIsConflated() {
        val base = source("session/shared/BaseSessionManager")
        val callback = base.substringAfter("override fun onPlaybackConfigChanged").substringBefore("audioManager.registerAudioPlaybackCallback")
        assertTrue(callback.indexOf("playbackObservation.invalidate()") < callback.indexOf("pollPlaybackChange()"))
        val refresh = base.substringAfter("private fun pollPlaybackChange()").substringBefore("fun pollOnce(")
        assertTrue(refresh.contains("if (playbackPollingJob?.isActive == true) return"))
        assertTrue(refresh.contains("playbackPollPending = true"))
        assertTrue(refresh.contains("while (!destroyed && playbackPollPending)"))
        val read = base.substringAfter("private suspend fun pollSessionDump").substringBefore("private fun pollPlaybackChange")
        assertTrue(read.indexOf("playbackObservation.snapshot()") < read.indexOf("dumpManager.dumpSessions()"))
        assertTrue(read.contains("playbackObservation.isCurrent(observedPlayback)"))
        assertTrue(read.contains("withContext(Dispatchers.IO) { dumpManager.dumpSessions() }"))
        val methodChange = base.substringAfter("override fun onDumpMethodChange").substringBefore("override fun onReceive")
        assertTrue(methodChange.indexOf("playbackObservation.invalidate()") < methodChange.indexOf("pollOnce(true)"))
    }

    @Test fun confirmationIsOneDelayedFreshPollAndCancelledOnManagerStop() {
        val manager = source("session/root/RootSessionDumpManager")
        val confirm = manager.substringAfter("fun requestPlaybackActivityConfirmation").substringBefore("override fun destroy")
        assertTrue(confirm.contains("if (confirmation != null) return"))
        assertTrue(confirm.contains("generation != confirmationGeneration"))
        assertTrue(confirm.contains("pollOnce(true)"))
        assertTrue(confirm.contains("postDelayed(it, 1_000)"))
        assertFalse(confirm.contains("scheduleWithFixedDelay"))
        val destroy = manager.substringAfter("override fun destroy").substringBefore("override fun onDumpMethodChange")
        assertTrue(destroy.contains("disposed = true"))
        assertTrue(destroy.contains("requestPlaybackActivityConfirmation(false)"))
    }

    @Test fun invalidDumpCannotReenableIdleAndObservationRunsWithoutDiscovery() {
        val service = source("service/RootAudioProcessorService")
        assertFalse(service.contains("if (dump == null) app.rootSessionDatabase.clearPlaybackActivity()"))
        assertTrue(service.contains("if (dump != null)"))
        assertTrue(service.contains("else app.rootSessionDatabase.updatePlaybackActivity(dump)"))
        assertTrue(service.contains("manager.requestPlaybackActivityConfirmation(dump != null && app.rootSessionDatabase.needsPlaybackActivityConfirmation)"))
        assertTrue(service.contains("app.rootSessionDatabase.activeProcessingSessions()"))
    }

    @Test fun ordinaryInactiveSessionsAreRemovedAndUnifiedRetentionIsNarrow() {
        val database = source("session/root/RootSessionDatabase")
        assertTrue(database.contains("playbackActivity.shouldProcess(id, uid, packageName)"))
        assertTrue(database.contains("!app.isGlobalMode && unifiedSessionRetentionEnabled()"))
        assertTrue(database.contains("unifiedSessions.shouldRetain(id, uid, packageName)"))
        val update = database.substringAfter("override fun onSessionDumpUpdated").substringBefore("fun clearPlaybackActivity")
        assertTrue(update.contains("playbackActivity.confirmedInactiveSessions()"))
        assertTrue(update.contains("removeSession(id)"))
        assertTrue(update.contains("app.isEnhancedProcessing && inactive[id]"))
        val addGate = database.substringAfter("override fun shouldAddSession").substringBefore("override fun onSessionRemoved")
        assertTrue(addGate.contains("shouldProcess(id, uid, packageName)"))
        assertTrue(addGate.contains("id !in supplementalSessionReservations"))
    }

    @Test fun reopenedSessionCancelsDelayedCloseWhileGlobalSessionStillClosesImmediately() {
        val service = source("service/RootAudioProcessorService")
        val open = service.substringAfter("AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->")
            .substringBefore("AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->")
        val close = service.substringAfter("AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->")
            .substringBefore("return START_REDELIVER_INTENT")
        val delayedClose = service.substringAfter("private fun scheduleSessionClose")
            .substringBefore("private fun cancelPendingSessionClose")

        assertTrue(open.indexOf("cancelPendingSessionClose(sessionId)") < open.indexOf("addSessionByIntent(intent)"))
        assertTrue(close.contains("if (sessionId == 0) removeClosedSession(intent)"))
        assertTrue(close.contains("else scheduleSessionClose(intent, sessionId)"))
        assertTrue(delayedClose.contains("delay(SESSION_CLOSE_GRACE_MILLIS)"))
        assertTrue(delayedClose.contains("sessionCloseGrace.isCurrent(sessionId, generation)"))
        assertTrue(service.contains("const val SESSION_CLOSE_GRACE_MILLIS = 10_000L"))
    }

    @Test fun enhancedSessionModeCanPrepareExplicitOpenBeforeAudioFlingerDiscovery() {
        val service = source("service/RootAudioProcessorService")
        val open = service.substringAfter("AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->")
            .substringBefore("AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->")
        val database = source("session/root/RootSessionDatabase")
        val earlyOpen = database.substringAfter("fun addSessionFromEarlyOpenIntent")
            .substringBefore("fun removeSessionByIntent")

        assertTrue(open.contains("!app.isGlobalMode && app.isEnhancedProcessing && sessionId > 0"))
        assertTrue(open.contains("addSessionFromEarlyOpenIntent(intent)"))
        assertTrue(earlyOpen.contains("if (app.isGlobalMode) return false"))
        assertTrue(earlyOpen.contains("if (sessionId <= 0) return false"))
        assertTrue(earlyOpen.contains("playbackActivity.forget(sessionId)"))
        assertTrue(earlyOpen.contains("addSession(sessionId, uid, packageName)"))
        assertFalse(earlyOpen.contains("addSession(sessionId, uid, packageName, true)"))
    }
}
