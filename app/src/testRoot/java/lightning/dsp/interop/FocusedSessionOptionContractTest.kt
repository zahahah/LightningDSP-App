package lightning.dsp.interop

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural boundaries complement the executable focus registry tests; not a device UI test. */
class FocusedSessionOptionContractTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun read(path: String) = File(app, path).readText()

    @Test fun keepEffectsReadyIsSessionOnlyIndependentOfAAudioAndKeepsRealSessions() {
        val defaults = read("src/main/res/values/defaults.xml")
        val ui = read("src/main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt")
        val xml = read("src/main/res/xml/app_audio_format_preferences.xml")
        val database = read("src/main/java/lightning/dsp/session/root/RootSessionDatabase.kt")
        val policy = read("src/main/java/lightning/dsp/session/root/KeepEffectsReadyPolicy.kt")
        val manager = read("src/main/java/lightning/dsp/session/root/RootSessionDumpManager.kt")
        val manifest = read("src/root/AndroidManifest.xml")
        val option = read("src/root/java/lightning/dsp/interop/KeepEffectsReadyOption.kt")
        val observer = read("src/main/java/lightning/dsp/session/root/ForegroundAppObserver.kt")
        assertTrue(defaults.contains("name=\"default_audioformat_keep_effects_ready\" translatable=\"false\">false"))
        assertTrue(ui.contains("isVisible = isRoot() && !preferences.get<Boolean>(R.string.key_audioformat_processing)"))
        assertTrue(ui.contains("isEnabled = isVisible"))
        assertFalse(ui.contains("keepEffectsReady?.isEnabled = isVisible && AAudioCompatibilityOption"))
        assertTrue(xml.indexOf("key_audioformat_enhanced_processing\"") < xml.indexOf("key_audioformat_keep_effects_ready\""))
        assertTrue(manifest.contains("android.permission.PACKAGE_USAGE_STATS"))
        assertTrue(option.contains("cmd appops set --user"))
        assertTrue(option.contains("PREVIOUS_USAGE_ACCESS_MODE"))
        assertTrue(option.contains("restorePreviousUsageAccess(context)"))
        assertTrue(observer.contains("usageStats?.queryEvents(begin, currentTime)"))
        assertTrue(observer.contains("UsageEvents.Event.ACTIVITY_RESUMED"))
        assertTrue(database.contains("shouldRetainMissingSession"))
        assertTrue(policy.contains("packageName in mediaAvailablePackages"))
        assertTrue(manager.contains("setMediaAvailablePackages(packages)"))
        assertTrue(database.contains("!app.isGlobalMode && unifiedSessionRetentionEnabled() && unifiedFocus.isFocused(uid, packageName)"))
        assertTrue(database.contains("unifiedSessions.shouldRetain(id, uid, packageName)"))
        assertTrue(database.contains("playbackActivity.isPlaying(id, session.uid, session.packageName)"))
        assertTrue(database.contains("enabled && isPackageDspEnabled(session.packageName) && allowed"))
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

    @Test fun activeProcessingIncludesFocusedRetentionButNotOrdinaryIdleOrDisabledSessions() {
        val projection = read("src/main/java/lightning/dsp/session/root/SessionPlaybackActivity.kt")
        val database = read("src/main/java/lightning/dsp/session/root/RootSessionDatabase.kt")
        assertTrue(projection.contains("playing || focusedRetention"))
        assertTrue(database.contains("focusedRetention = shouldKeepUnified(id, session.uid, session.packageName)"))
        assertTrue(database.contains("processingEnabled = enabled && isPackageDspEnabled(session.packageName)"))
    }
}
