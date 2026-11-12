package lightning.dsp.interop

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural boundaries complement the executable focus registry tests; not a device UI test. */
class FocusedSessionOptionContractTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun read(path: String) = File(app, path).readText()

    @Test fun focusRetentionIsScopedToVerifiedReusedAAudioSession() {
        val xml = read("src/main/res/xml/app_audio_format_preferences.xml")
        val ui = read("src/main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt")
        val option = read("src/root/java/lightning/dsp/interop/KeepEffectsReadyOption.kt")
        val focused = read("src/root/java/lightning/dsp/interop/AAudioFocusedSessionOption.kt")
        val database = read("src/main/java/lightning/dsp/session/root/RootSessionDatabase.kt")
        assertFalse(xml.contains("key_audioformat_keep_effects_ready"))
        assertFalse(ui.contains("keepEffectsReady"))
        assertTrue(option.contains("restorePreviousUsageAccess(context)"))
        assertTrue(option.contains("val preparationValue = \"0\""))
        assertTrue(focused.contains("AAudioSessionReuseOption.requested(context)"))
        assertTrue(focused.contains("AAudioCompatibilityOption.requested(context)"))
        assertTrue(database.contains("unifiedSessions.shouldRetain(id, uid, packageName)"))
        assertTrue(database.contains("unifiedFocus.isFocused(uid, packageName)"))
    }

    @Test fun focusUsesRealWindowsAndDoesNotDependOnVolumeManagerOrPermissionExpansion() {
        val manifest = read("src/root/AndroidManifest.xml")
        val tracker = read("src/root/java/lightning/dsp/interop/ScopedAppFocusTracker.java")
        val provider = read("src/root/java/lightning/dsp/interop/CompatibilityPreparationProvider.kt")
        assertTrue(manifest.contains("android:forceQueryable=\"true\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(tracker.contains("addOnWindowFocusChangeListener"))
        assertTrue(tracker.contains("removeOnWindowFocusChangeListener"))
        assertTrue(tracker.contains("activity.hasWindowFocus()"))
        assertTrue(tracker.contains("if (focused) main.postDelayed(refresh, 2_000L)"))
        assertFalse(tracker.contains("LightningDspVolumeManager"))
        assertTrue(provider.contains("getPackagesForUid(uid)?.singleOrNull()"))
        assertTrue(provider.contains("unifiedOwners.verify(owner, uid, pid, deadline)"))
        assertTrue(provider.contains("AAudioFocusedSessionOption.requested(app)"))
    }

    @Test fun focusedRetentionDoesNotReportIdleSessionsAsActivelyProcessing() {
        val projection = read("src/main/java/lightning/dsp/session/root/SessionPlaybackActivity.kt")
        val database = read("src/main/java/lightning/dsp/session/root/RootSessionDatabase.kt")
        assertTrue(projection.contains("playing || focusedRetention"))
        assertTrue(database.contains("focusedRetention = false"))
        assertTrue(database.contains("processingEnabled = enabled && isPackageDspEnabled(session.packageName)"))
        val processing = database.substringAfter("private fun shouldProcess(").substringBefore("fun activeProcessingSessions()")
        assertFalse(processing.contains("shouldKeepEffectsReady("))
        assertTrue(processing.contains("shouldKeepUnified("))
    }
}
