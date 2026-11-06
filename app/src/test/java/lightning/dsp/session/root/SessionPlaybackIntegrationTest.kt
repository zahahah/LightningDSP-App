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

    @Test fun invalidDumpAndStoppingScanningRestoreUnknownActivity() {
        val service = source("service/RootAudioProcessorService")
        assertTrue(service.contains("if (dump == null) app.rootSessionDatabase.clearPlaybackActivity()"))
        assertTrue(service.contains("manager.requestPlaybackActivityConfirmation(app.rootSessionDatabase.needsPlaybackActivityConfirmation)"))
        assertEquals(2, Regex("sessionDumpManager = null\\s+app.rootSessionDatabase.clearPlaybackActivity\\(\\)")
            .findAll(service).count())
    }

    @Test fun activityChangesKeepAttachmentsAndRespectGlobalAndPerAppGates() {
        val database = source("session/root/RootSessionDatabase")
        assertTrue(database.contains("id <= 0 || !app.isEnhancedProcessing || playbackActivity.shouldProcess"))
        val apply = database.substringAfter("private fun applyPlaybackActivity()").substringBefore("override fun shouldAcceptSessionDump")
        assertTrue(apply.contains("if (id <= 0) return@forEach"))
        assertTrue(apply.contains("enabled && isPackageDspEnabled(session.packageName) && allowed"))
        assertTrue(apply.contains("actual == desired"))
        assertTrue(apply.indexOf("getOrNull() != desired") < apply.indexOf("recordPlaybackSuspension("))
        assertFalse(apply.contains("close()"))
        assertFalse(apply.contains("removeSession("))
        assertFalse(apply.contains("LightningDspRemoteEngine("))
        assertFalse(apply.contains("setEnabledIfSupported"))
    }
}
