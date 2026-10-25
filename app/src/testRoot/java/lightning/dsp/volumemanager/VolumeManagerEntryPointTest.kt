package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-set/startup boundaries, not proof of Android service behavior. */
class VolumeManagerEntryPointTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun source(path: String) = File(app, "src/$path").readText()

    @Test fun onlyAnExplicitMainScreenEntryResumesThePersistedManager() {
        assertTrue(source("main/java/lightning/dsp/activity/MainActivity.kt")
            .contains("VolumeManagerLifecycle.onForegroundEntry(this)"))
        val entry = source("root/java/lightning/dsp/flavor/VolumeManagerLifecycle.kt")
        assertTrue(entry.contains("getBoolean(\"enabled\", false)"))
        assertTrue(entry.contains("if (!enabled) return"))
        assertTrue(entry.contains("runCatching"))
        assertTrue(entry.contains("LightningDspVolumeManager.initialize(appContext)"))
        assertTrue(entry.contains("LightningDspVolumeManager.resume()"))
        assertFalse(source("main/java/lightning/dsp/MainApplication.kt")
            .contains("VolumeManagerLifecycle.onForegroundEntry"))
    }

    @Test fun installerAndLauncherUseTheLightningDSPArtwork() {
        val manifest = source("main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_dsp_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_dsp_launcher\""))
        val icon = File(app, "src/main/res/drawable-nodpi/lightningdsp_launcher.png")
        assertTrue(icon.isFile && icon.length() > 0)
    }

    @Test fun otherFlavorsCannotStartTheRootManager() {
        for (flavor in listOf("rootless", "plugin")) {
            val entry = source("$flavor/java/lightning/dsp/flavor/VolumeManagerLifecycle.kt")
            assertFalse(entry.contains("LightningDspVolumeManager"))
            assertFalse(entry.contains("startService"))
            assertFalse(entry.contains("startForegroundService"))
        }
    }
}
