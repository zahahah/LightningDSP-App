package lightning.dsp.service

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BackgroundAudioStartupPolicyTest {
    @Test fun backgroundStartupDoesNotEnableLegacyOrReplaceAnExistingGlobalSession() {
        assertTrue(BackgroundAudioStartupPolicy.shouldOpenGlobalSession(true, false))
        assertFalse(BackgroundAudioStartupPolicy.shouldOpenGlobalSession(true, true))
        assertFalse(BackgroundAudioStartupPolicy.shouldOpenGlobalSession(false, false))
        assertFalse(BackgroundAudioStartupPolicy.shouldOpenGlobalSession(false, true))
    }

    @Test fun missingDescriptorRetriesAreFiniteAndUnsupportedDoesNotRetry() {
        val retries = (0 until BackgroundAudioStartupPolicy.MAX_ATTEMPTS).count {
            BackgroundAudioStartupPolicy.shouldRetry(it, false)
        }
        assertEquals(14, retries)
        assertTrue(retries * BackgroundAudioStartupPolicy.RETRY_INTERVAL_MS < 30_000)
        assertFalse(BackgroundAudioStartupPolicy.shouldRetry(0, true))
    }

    @Test fun bootAndLateLoadReusePrivateForegroundServiceWithoutAnActivity() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
        val service = File(app, "src/main/java/lightning/dsp/service/RootAudioProcessorService.kt").readText()
        val boot = File(app, "src/main/java/lightning/dsp/receiver/BootCompletedReceiver.kt").readText()
        val manifest = File(app, "src/root/AndroidManifest.xml").readText()
        assertTrue(service.contains(".root.service.START_BACKGROUND"))
        assertTrue(service.contains("withContext(Dispatchers.IO)"))
        assertTrue(service.contains("backgroundScope.cancel()"))
        assertTrue(service.contains("backgroundStartup?.isActive == true"))
        assertTrue(service.contains("sessionDumpManager == null"))
        assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        assertTrue(boot.contains("RootAudioProcessorService.startBackground(context)"))
        assertTrue(manifest.contains("mediaPlayback|specialUse"))
        assertTrue(manifest.contains("tools:replace=\"android:foregroundServiceType\""))
        val helper = service.substringAfter("fun startBackground(context: Context)")
            .substringBefore("fun startService(context: Context")
        assertFalse(helper.contains("isPluginInstalled"))
        assertFalse(helper.contains("startActivity"))
        assertFalse(service.contains("preferences.set("))
    }
    @Test fun staleSessionRecordDoesNotPreventRecoveryAndStopBypassesDiscovery() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
        val service = File(app, "src/main/java/lightning/dsp/service/RootAudioProcessorService.kt").readText()
        assertTrue(service.contains("app.rootSessionDatabase.hasUsableGlobalSession()"))
        assertTrue(service.contains("app.rootSessionDatabase.removeSession(0)"))
        assertTrue(service.contains("if(intent.action != ACTION_STOP &&"))
        assertTrue(BackgroundAudioStartupPolicy.shouldOpenGlobalSession(true, false))
        val background = service.substringAfter("private fun startBackgroundDiscovery()")
            .substringBefore("private fun initializeProcessingIfNeeded()")
        assertFalse(background.contains("clearSessions()"))
    }

    @Test fun authorizedBackgroundStartupResumesTheMixerBeforeDspDiscoveryCanFail() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
        val service = File(app, "src/main/java/lightning/dsp/service/RootAudioProcessorService.kt").readText()
        val sticky = service.substringAfter("if (intent == null)")
            .substringBefore("if (intent.action == ACTION_STOP)")
        val requested = service.substringAfter("if (intent.action == ACTION_START_BACKGROUND && isRoot())")
            .substringBefore("if(LightningDspRemoteEngine.discoveredPluginState()")
        for (entry in listOf(sticky, requested)) {
            val mixer = entry.indexOf("VolumeManagerLifecycle.onBackgroundEntry(this)")
            val discovery = entry.indexOf("startBackgroundDiscovery()")
            assertTrue("Mixer resume must precede descriptor discovery", mixer >= 0 && discovery > mixer)
        }
        assertTrue(sticky.contains("if (isRoot())"))
    }

}
