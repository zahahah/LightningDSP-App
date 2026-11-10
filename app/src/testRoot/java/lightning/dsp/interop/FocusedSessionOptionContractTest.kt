package lightning.dsp.interop

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural boundaries complement the executable focus registry tests; not a device UI test. */
class FocusedSessionOptionContractTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun read(path: String) = File(app, path).readText()

    @Test fun optionIsOptInSessionOnlyAndPreservesNormalPlayback() {
        val defaults = read("src/main/res/values/defaults.xml")
        val ui = read("src/main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt")
        val database = read("src/main/java/lightning/dsp/session/root/RootSessionDatabase.kt")
        assertTrue(defaults.contains("name=\"default_audioformat_aaudio_focus_hold\" translatable=\"false\">false"))
        assertTrue(ui.contains("isVisible = isRoot() && !preferences.get<Boolean>(R.string.key_audioformat_processing)"))
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
