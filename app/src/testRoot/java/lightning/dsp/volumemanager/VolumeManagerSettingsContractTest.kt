package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks only: actual preference lifecycle and view rendering require Android. */
class VolumeManagerSettingsContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun backend() = File(app,
        "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeManager.kt").readText()
    private fun ui(name: String) = File(app,
        "src/root/java/lightning/dsp/volumemanager/ui/$name.kt").readText()

    @Test fun cardVisibilityHasOnePersistedSettingForFullAndFloatingPanels() {
        val activity = ui("LightningDspVolumeManagerActivity")
        val composeUi = ui("LightningVolumeComposeUi")
        val overlay = ui("LightningVolumeOverlay")
        assertTrue(activity.contains("getSharedPreferences(LightningVolumePanel.PREFERENCES, MODE_PRIVATE)"))
        assertTrue(activity.contains("putBoolean(\"show_dsp_card\", checked).apply()"))
        assertTrue(composeUi.contains("getBoolean(\"show_dsp_card\", true)"))
        assertTrue(composeUi.contains("if (showDspCard"))
        assertTrue(composeUi.contains("registerOnSharedPreferenceChangeListener(listener)"))
        assertTrue(composeUi.contains("unregisterOnSharedPreferenceChangeListener(listener)"))
        assertTrue(activity.contains("LightningVolumeManagerScreen("))
        assertTrue(composeUi.contains("internal fun LightningVolumeFloatingPanel("))
        assertTrue(overlay.contains("LightningVolumeFloatingPanel("))
        assertTrue(composeUi.contains("SystemVolumeControls(context, compact = true, showVisibilityOptions = false)"))
    }

    @Test fun managerAndPerAppOptInsRemainIndependentOfCardVisibility() {
        val manager = backend()
        val activity = ui("LightningDspVolumeManagerActivity")
        assertTrue(manager.contains("getBoolean(\"enabled\", false)"))
        assertTrue(manager.contains("getBoolean(\"per_app_enabled\", true)"))
        assertTrue(manager.contains("putBoolean(\"per_app_enabled\", enabled).apply()"))
        assertTrue(activity.contains("LightningDspVolumeManager.setPerAppEnabled(checked)"))
        assertFalse(manager.contains("show_dsp_card"))
    }

    @Test fun appSlidersUseNeutralGrayWhileLightningDspKeepsItsBlueTrackAndRoundEffects() {
        val composeUi = ui("LightningVolumeComposeUi")
        val dspCard = ui("LightningDspVolumeCard")
        val appTrack = composeUi.substringAfter("private fun AppVolumeTrack(")
            .substringBefore("private fun AppOptions(")
        assertTrue(appTrack.contains("baseTrackColor = SystemVolumeBaseTrack"))
        assertTrue(appTrack.contains("progressFillColor = SystemVolumeProgressTrack"))
        assertTrue(appTrack.contains("trackContentColor = SystemVolumeContent"))
        assertTrue(appTrack.contains("progressContentColor = SystemVolumeContent"))
        assertFalse(appTrack.contains("0xFF607D8B"))
        assertFalse(appTrack.contains("isSystemInDarkTheme"))
        assertTrue(dspCard.contains("progressFillColor = DspBlue"))
        assertTrue(dspCard.contains(".size(42.dp).clip(CircleShape)"))
        assertTrue(dspCard.contains("DspRoundControl("))
    }

    @Test fun systemSlidersUseLightFillDarkBaseAndDarkIconsWhileFloatingPanelHasNoAllAppsEmptyState() {
        val composeUi = ui("LightningVolumeComposeUi")
        val overlay = ui("LightningVolumeOverlay")
        val floatingPanel = composeUi.substringAfter("internal fun LightningVolumeFloatingPanel(")
            .substringBefore("@Composable\nprivate fun ManagerStatus")
        assertTrue(composeUi.contains("private val SystemVolumeBaseTrack = Color(0xFFB0BEC5)"))
        assertTrue(composeUi.contains("private val SystemVolumeProgressTrack = Color(0xFFEEF1F3)"))
        assertTrue(composeUi.contains("private val SystemVolumeContent = Color(0xFF263238)"))
        assertTrue(composeUi.contains("streamIconTint = SystemVolumeContent"))
        assertTrue(composeUi.contains("baseTrackColor = SystemVolumeBaseTrack"))
        assertTrue(composeUi.contains("progressFillColor = SystemVolumeProgressTrack"))
        assertTrue(composeUi.contains("setSystemStreamVolume(stream, it.roundToInt())"))
        assertTrue(composeUi.contains("putBoolean(\"stream_\$stream\", checked)"))
        assertFalse(composeUi.contains("showCallVolume"))
        assertFalse(composeUi.contains("callNow ||"))
        assertFalse(floatingPanel.contains("No active players"))
        assertFalse(floatingPanel.contains("Show all apps"))
        assertFalse(overlay.contains("onShowAllApps"))
    }

    @Test fun inactiveAppControlsRemainEditableAndCatalogSupportsSearchAndSystemFiltering() {
        val composeUi = ui("LightningVolumeComposeUi")
        val appTrack = composeUi.substringAfter("private fun AppVolumeTrack(")
            .substringBefore("private fun AppOptions(")
        val options = composeUi.substringAfter("private fun AppOptions(")
            .substringBefore("@Composable\ninternal fun TrackSurface")
        assertTrue(appTrack.contains("val controllable = app.controllable"))
        assertFalse(appTrack.contains("state.connected"))
        assertFalse(appTrack.contains("state.perAppEnabled"))
        assertTrue(options.contains("Switch(checked = muted, enabled = controllable"))
        assertTrue(options.contains("Slider(value = pan, enabled = controllable"))
        assertTrue(options.contains("onHiddenChange(!hidden)"))
        assertTrue(composeUi.contains("hide_system_apps"))
        assertTrue(composeUi.contains("it.label.contains(query, ignoreCase = true)"))
        assertTrue(composeUi.contains("it.packageName.contains(query, ignoreCase = true)"))
        assertTrue(composeUi.contains("Icons.Default.Search"))
        assertTrue(composeUi.contains("AppSearchField(searchQuery"))
    }

    @Test fun generalVolumeAndHardwareKeysUseTheSeparateBoundedRootOperationPath() {
        val manager = backend()
        val root = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeRoot.kt").readText()
        val session = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/VolumePipeSession.kt").readText()
        val accessibility = ui("LightningVolumeAccessibilityService")
        assertTrue(manager.contains("SystemVolumeOperation.set(nextOperationId()"))
        assertTrue(manager.contains("SystemVolumeOperation.adjust(nextOperationId()"))
        assertTrue(manager.contains("offerVolumeOperation(operation)"))
        assertTrue(root.contains("SystemVolumeOperationQueue(capacity = 32)"))
        assertTrue(root.contains("setStreamVolume(operation.stream, operation.value, 0)"))
        assertTrue(root.contains("adjustSuggestedStreamVolume(operation.value, operation.stream, 0)"))
        assertTrue(session.contains("pendingSettings: String?"))
        assertTrue(session.contains("pendingVolumeOperations"))
        assertTrue(accessibility.contains("LightningDspVolumeManager.adjustSystemVolume(direction)"))
        assertTrue(accessibility.contains("if (!adjust())"))
    }

    @Test fun floatingPanelThemeFollowsDeviceByDefaultAndSupportsLightDarkOverrides() {
        val theme = ui("LightningVolumePanelTheme")
        val activity = ui("LightningDspVolumeManagerActivity")
        val composeUi = ui("LightningVolumeComposeUi")
        val resources = File(app, "src/root/res/values/lightning_volume_manager.xml").readText()
        assertTrue(theme.contains("SYSTEM(\"system\""))
        assertTrue(theme.contains("LIGHT(\"light\""))
        assertTrue(theme.contains("DARK(\"dark\""))
        assertTrue(theme.contains("const val PREFERENCE_KEY = \"floating_panel_theme\""))
        assertTrue(activity.contains("R.string.ldsp_vm_floating_theme_title"))
        assertTrue(activity.contains("setSingleChoiceItems(themes.map"))
        assertTrue(activity.contains("putString(LightningVolumePanelTheme.PREFERENCE_KEY, theme.preferenceValue)"))
        assertTrue(composeUi.contains("panelTheme.isDark(androidx.compose.foundation.isSystemInDarkTheme())"))
        assertTrue(composeUi.contains("LightningVolumePanelTheme.PREFERENCE_KEY -> panelTheme"))
        assertTrue(resources.contains("ldsp_vm_theme_system"))
        assertTrue(resources.contains("ldsp_vm_theme_light"))
        assertTrue(resources.contains("ldsp_vm_theme_dark"))
    }

    @Test fun floatingPanelDismissesAfterTimeoutWithStationaryHalfSecondFade() {
        val overlay = ui("LightningVolumeOverlay")
        val hide = overlay.substringAfter("fun hide()").substringBefore("private fun scheduleDismiss")
        assertTrue(overlay.contains("private const val DISMISS_TIMEOUT_MS = 2_500L"))
        assertTrue(overlay.contains("private const val FADE_OUT_DURATION_MS = 500L"))
        assertTrue(hide.contains("current.animate().alpha(0f).setDuration(FADE_OUT_DURATION_MS)"))
        assertTrue(hide.indexOf("withEndAction") < hide.indexOf("Lifecycle.State.DESTROYED"))
        assertFalse(hide.contains("translationX"))
        assertFalse(hide.contains("translationY"))
    }

    @Test fun enablingRequestsOverlayPermissionAndOnlyStartsAfterGrant() {
        val activity = ui("LightningDspVolumeManagerActivity")
        assertTrue(activity.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
        assertTrue(activity.contains("enableAfterOverlayPermission = true"))
        assertTrue(activity.contains("if (Settings.canDrawOverlays(this))"))
        assertTrue(activity.contains("onManagerEnabledChange = ::setManagerEnabled"))
    }

    @Test fun floatingComposeWindowOwnsLifecycleBeforeWindowAttachment() {
        val overlay = ui("LightningVolumeOverlay")
        val lifecycleOwner = overlay.indexOf("host.setViewTreeLifecycleOwner(owner)")
        val savedStateOwner = overlay.indexOf("host.setViewTreeSavedStateRegistryOwner(owner)")
        val themedContext = overlay.indexOf("ContextThemeWrapper(context.applicationContext, lightning.dsp.R.style.Theme_RootlessLightningDSP)")
        val attachment = overlay.indexOf("manager.addView(host, params)")
        assertTrue(lifecycleOwner >= 0 && lifecycleOwner < attachment)
        assertTrue(savedStateOwner >= 0 && savedStateOwner < attachment)
        assertTrue(themedContext >= 0 && themedContext < attachment)
    }
}
