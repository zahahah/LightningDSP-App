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

    @Test fun resumingTheManagerDoesNotOpenTheFloatingPanelAutomatically() {
        val service = source("root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeService.kt")
        val onCreate = service.substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        assertFalse(onCreate.contains("LightningVolumeOverlay.show"))

        // The panel remains available from the manager screen when explicitly requested.
        assertTrue(source("root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt")
            .contains("onShowOverlay = { LightningVolumeOverlay.show(this) }"))
    }

    @Test fun volumeButtonsCanOpenThePanelBeforeItHasEverBeenShownManually() {
        val manager = source("root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeManager.kt")
        val settings = manager.substringAfter("private fun sendSettings()")
            .substringBefore("private fun publish(")
        assertTrue(settings.contains(".put(\"panel_enabled\", isEnabled && Settings.canDrawOverlays(context))"))
        assertFalse(settings.contains("isEnabled && overlayReady"))

        val showReply = manager.substringAfter("if (response.optBoolean(\"show_panel\"))")
            .substringBefore("if (response.has(\"volume_operation_result\"))")
        assertTrue(showReply.contains("if (isEnabled && Settings.canDrawOverlays(context)) LightningVolumeOverlay.show(context)"))
        assertFalse(showReply.contains("overlayReady"))

        val accessibility = source("root/java/lightning/dsp/volumemanager/ui/LightningVolumeAccessibilityService.kt")
        val eligible = accessibility.substringAfter("private fun eligible()")
            .substringBefore("private fun adjust()")
        assertFalse(eligible.contains("isOverlayReady"))
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
