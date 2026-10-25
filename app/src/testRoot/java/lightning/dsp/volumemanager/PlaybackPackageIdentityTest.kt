package lightning.dsp.volumemanager

import java.io.File
import lightning.dsp.volumemanager.backend.PlaybackVolumePolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackPackageIdentityTest {
    @Test fun aRemovedPackagesUidCannotTransferItsRuleToTheNextOwner() {
        val expected = "com.example.player"
        assertTrue(PlaybackVolumePolicy.matchesPackage(expected, listOf(expected)))
        assertFalse(PlaybackVolumePolicy.matchesPackage(expected, emptyList()))
        assertFalse(PlaybackVolumePolicy.matchesPackage(expected, listOf("com.example.replacement")))
    }

    @Test fun sharedUidRemainsAmbiguousEvenWhenItIncludesTheExpectedPackage() {
        assertFalse(PlaybackVolumePolicy.matchesPackage("com.example.player",
            listOf("com.example.player", "com.example.shared")))
    }

    @Test fun exemptionIsOnlyRequiredOnAndroidVersionsThatEnforceHiddenApiAccess() {
        assertFalse(PlaybackVolumePolicy.requiresHiddenApiExemption(26))
        assertFalse(PlaybackVolumePolicy.requiresHiddenApiExemption(27))
        assertTrue(PlaybackVolumePolicy.requiresHiddenApiExemption(28))
        assertTrue(PlaybackVolumePolicy.requiresHiddenApiExemption(36))
    }

    /** Source wiring only. Actual broadcast delivery and UID reuse require Android testing. */
    @Test fun packageChangesRefreshTheCatalogAndSendFreshRules() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/root/AndroidManifest.xml").isFile }
        val base = File(app, "src/root/java/lightning/dsp/volumemanager/backend")
        val manager = File(base, "LightningDspVolumeManager.kt").readText()
        val root = File(base, "LightningDspVolumeRoot.kt").readText()
        val receiver = Regex("""override fun onReceive\(context: Context, intent: Intent\)\s*\{\s*refresh\(\)\s*}\s*}, IntentFilter\(\)\.apply\s*\{(.*?)}, ContextCompat.RECEIVER_NOT_EXPORTED""",
            RegexOption.DOT_MATCHES_ALL).find(manager)?.groupValues?.get(1)
        assertNotNull("Package receiver must refresh without an exported app IPC entry", receiver)
        for (action in listOf("ADDED", "REMOVED", "CHANGED")) {
            assertTrue(receiver!!.contains("addAction(Intent.ACTION_PACKAGE_$action)"))
        }
        assertTrue(receiver!!.contains("addDataScheme(\"package\")"))
        val refresh = manager.substringAfter("fun refresh() {").substringBefore("fun setAppVolume")
        assertTrue(refresh.contains("getInstalledApplications(0)"))
        assertTrue(refresh.contains("uidCounts = applications.groupingBy { it.uid }.eachCount()"))
        assertTrue(refresh.contains("sendSettings()"))
        assertTrue(manager.contains(".put(\"package\", name)"))
        assertTrue(root.contains("PlaybackVolumePolicy.matchesPackage"))
        assertTrue(root.contains("getPackagesForUid(uid)"))
    }
}
