package lightning.dsp.volumemanager

import java.io.File
import lightning.dsp.volumemanager.backend.VolumeManagerStartupPolicy
import org.junit.Assert.*
import org.junit.Test

/** Source-set/startup boundaries, not proof of Android service behavior. */
class VolumeManagerEntryPointTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun source(path: String) = File(app, "src/$path").readText()

    @Test fun foregroundAndAuthorizedBackgroundEntriesResumeOnlyThePersistedManager() {
        assertTrue(source("main/java/lightning/dsp/activity/MainActivity.kt")
            .contains("VolumeManagerLifecycle.onForegroundEntry(this)"))
        val entry = source("root/java/lightning/dsp/flavor/VolumeManagerLifecycle.kt")
        assertTrue(entry.contains("getBoolean(\"enabled\", false)"))
        assertTrue(entry.contains("if (!enabled) return"))
        assertTrue(entry.contains("fun onBackgroundEntry(context: Context) = resume(context, background = true)"))
        assertTrue(entry.contains("if (background && !VolumeManagerStartupPolicy.shouldResume"))
        assertTrue(entry.contains("getBoolean(\"startup_on_boot\", VolumeManagerStartupPolicy.DEFAULT_STARTUP_ON_BOOT)"))
        assertTrue(entry.contains("runCatching"))
        assertTrue(entry.contains("LightningDspVolumeManager.initialize(appContext)"))
        assertTrue(entry.contains("LightningDspVolumeManager.resume()"))
        assertFalse(source("main/java/lightning/dsp/MainApplication.kt")
            .contains("VolumeManagerLifecycle.onForegroundEntry"))

        val receiver = source("root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeStartupReceiver.kt")
        assertTrue(receiver.contains("intent.action != Intent.ACTION_BOOT_COMPLETED"))
        assertTrue(receiver.contains("VolumeManagerLifecycle.onBackgroundEntry(context)"))
        assertFalse(entry.contains("LightningVolumeOverlay.show"))
        assertFalse(entry.contains("setEnabled("))
    }

    @Test fun bootDefaultsOnOnlyForAnEnabledManagerAndPreservesExplicitOptOut() {
        assertTrue(VolumeManagerStartupPolicy.DEFAULT_STARTUP_ON_BOOT)
        assertTrue(VolumeManagerStartupPolicy.shouldResume(enabled = true))
        assertTrue(VolumeManagerStartupPolicy.shouldResume(enabled = true, startupOnBoot = true))
        assertFalse(VolumeManagerStartupPolicy.shouldResume(enabled = true, startupOnBoot = false))
        assertFalse(VolumeManagerStartupPolicy.shouldResume(enabled = false))
        assertFalse(VolumeManagerStartupPolicy.shouldResume(enabled = false, startupOnBoot = true))
        assertFalse(VolumeManagerStartupPolicy.shouldResume(enabled = false, startupOnBoot = false))
        val manager = source("root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeManager.kt")
        assertTrue(manager.contains("getBoolean(\"startup_on_boot\", VolumeManagerStartupPolicy.DEFAULT_STARTUP_ON_BOOT)"))
        val setEnabled = manager.substringAfter("fun setEnabled(enabled: Boolean)")
            .substringBefore("private fun startService()")
        assertFalse(setEnabled.contains("\"startup_on_boot\""))
    }

    @Test fun resumingTheManagerDoesNotOpenTheFloatingPanelAutomatically() {
        val service = source("root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeService.kt")
        val onCreate = service.substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        assertFalse(onCreate.contains("LightningVolumeOverlay.show"))

        // The panel remains available from the manager screen when explicitly requested.
        assertTrue(source("root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt")
            .contains("onShowOverlay = ::showOverlayOrRequestPermission"))
    }

    @Test fun anAlreadyEnabledManagerCanRecoverRevokedOverlayPermissionFromItsFooter() {
        val activity = source("root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt")
        assertTrue(activity.contains("onShowOverlay = ::showOverlayOrRequestPermission"))
        assertTrue(activity.contains("if (!Settings.canDrawOverlays(this))"))
        assertTrue(activity.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
        val screen = source("root/java/lightning/dsp/volumemanager/ui/LightningVolumeComposeUi.kt")
        assertTrue(screen.contains("state.enabled && !Settings.canDrawOverlays(context)"))
        assertTrue(screen.contains("onClick = onShowOverlay"))
        val resumed = activity.substringAfter("override fun onResume()").substringBefore("private fun setManagerEnabled")
        assertTrue(resumed.contains("LightningDspVolumeManager.resume()"))
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
            assertTrue(entry.contains("fun onBackgroundEntry"))
        }
    }
}
