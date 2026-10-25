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
        assertTrue(composeUi.contains("SystemVolumeControls(context, compact = true, showVisibilityOptions = false, floatingLightTheme = !dark, floatingPanel = true)"))
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
        assertTrue(appTrack.contains("baseTrackColor = if (floatingLightTheme) FloatingLightBaseTrack else SystemVolumeBaseTrack"))
        assertTrue(appTrack.contains("progressFillColor = if (floatingLightTheme) FloatingLightProgressTrack else SystemVolumeProgressTrack"))
        assertTrue(appTrack.contains("trackContentColor = if (floatingLightTheme) FloatingIceWhite else AppVolumeContent"))
        assertTrue(appTrack.contains("progressContentColor = if (floatingLightTheme) FloatingIceWhite else AppVolumeContent"))
        assertTrue(composeUi.contains("private val AppVolumeContent = Color(0xFF101820)"))
        assertTrue(composeUi.contains("fontWeight = FontWeight.SemiBold"))
        assertFalse(appTrack.contains("0xFF607D8B"))
        assertFalse(appTrack.contains("isSystemInDarkTheme"))
        assertTrue(dspCard.contains("else -> DspBlue"))
        assertTrue(dspCard.contains(".size(42.dp).clip(CircleShape)"))
        assertTrue(dspCard.contains("DspRoundControl("))
    }

    @Test fun systemSlidersUseLightFillDarkBaseAndDarkIconsWhileFloatingPanelHasNoAllAppsEmptyState() {
        val composeUi = ui("LightningVolumeComposeUi")
        val overlay = ui("LightningVolumeOverlay")
        val floatingPanel = composeUi.substringAfter("internal fun LightningVolumeFloatingPanel(")
            .substringBefore("@Composable\nprivate fun ManagerStatus")
        assertTrue(composeUi.contains("private val SystemVolumeBaseTrack = Color(0xFF8D969C)"))
        assertTrue(composeUi.contains("private val SystemVolumeProgressTrack = Color(0xFFD1D5D8)"))
        assertTrue(composeUi.contains("private val SystemVolumeContent = Color(0xFF263238)"))
        assertTrue(composeUi.contains("streamIconTint = if (floatingLightTheme) FloatingIceWhite else SystemVolumeContent"))
        assertTrue(composeUi.contains("baseTrackColor = if (floatingLightTheme) FloatingLightBaseTrack else SystemVolumeBaseTrack"))
        assertTrue(composeUi.contains("progressFillColor = if (floatingLightTheme) FloatingLightProgressTrack else SystemVolumeProgressTrack"))
        assertTrue(composeUi.contains("setSystemStreamVolume(stream, it.roundToInt())"))
        assertTrue(composeUi.contains("putBoolean(\"stream_\$stream\", checked)"))
        assertFalse(composeUi.contains("showCallVolume"))
        assertFalse(composeUi.contains("callNow ||"))
        assertFalse(floatingPanel.contains("No active players"))
        assertFalse(floatingPanel.contains("Show all apps"))
        assertFalse(overlay.contains("onShowAllApps"))
    }

    @Test fun floatingThemesUseExplicitTracksAndDspTrackReachesTheRightEdge() {
        val composeUi = ui("LightningVolumeComposeUi")
        val dspCard = ui("LightningDspVolumeCard")
        assertTrue(composeUi.contains("FloatingLightProgressTrack = Color(0xFF4B535B)"))
        assertTrue(composeUi.contains("FloatingLightBaseTrack = Color(0xFF727C85)"))
        assertTrue(composeUi.contains("FloatingIceWhite = Color(0xFFF0F5F7)"))
        assertTrue(composeUi.contains("DspCard(onOpenEffect = onClose, floating = true, floatingLightTheme = !dark)"))
        assertTrue(composeUi.contains("AppVolumeTrack(app, showOptions = false, floatingLightTheme = !dark,"))
        assertTrue(dspCard.contains("end = if (floating) 0.dp else 14.dp"))
        assertTrue(dspCard.contains("floating -> Color(0xFFA6B6C4)"))
        assertTrue(dspCard.contains("dspTrackColors(monochromeBar, lightTheme, floating)"))
        assertTrue(dspCard.contains("monochrome && lightTheme -> FloatingLightBaseTrack to FloatingLightProgressTrack"))
    }

    @Test fun dspCardUsesPanelEffectNamesWithOnlyTheThreeRequestedAliasesAndCenteredLabels() {
        val card = ui("LightningDspVolumeCard")
        val sharedTitles = listOf("compander", "eq", "geq", "crossfeed", "tube", "ddc", "liveprog", "convolver")
        for (name in sharedTitles) assertTrue(card.contains("R.string.${name}_enable"))
        for (alias in listOf("bass", "stereo", "reverb")) assertTrue(card.contains("R.string.ldsp_vm_effect_$alias"))
        assertTrue(card.contains("start = if (floating) 0.dp else 14.dp"))
        val roundControl = card.substringAfter("private fun DspRoundControl(")
        assertTrue(roundControl.contains("textAlign = TextAlign.Center"))
        assertTrue(roundControl.contains("modifier = Modifier.fillMaxWidth()"))
        assertTrue(roundControl.contains("maxLines = 1"))
        assertTrue(roundControl.contains("Modifier.width(58.dp)"))
        assertTrue(roundControl.contains("fontSize = 9.sp"))
        assertTrue(roundControl.contains("softWrap = false"))
        assertTrue(roundControl.contains("overflow = TextOverflow.Clip"))
    }

    @Test fun effectButtonAbbreviationsMatchTheCurrentPanelNames() {
        val card = ui("LightningDspVolumeCard")
        val expected = mapOf(
            "compander" to "DRC", "bass" to "BASS", "equalizer" to "MEQ",
            "graphic_eq" to "AREQ", "reverb" to "REV", "stereo_wide" to "ST",
            "crossfeed" to "CF", "tube" to "AM", "ddc" to "DDC",
            "liveprog" to "DSP", "convolver" to "CONV",
        )
        for ((id, abbreviation) in expected) {
            val entry = card.lineSequence().single { it.contains("DspEffect(\"$id\",") }
            assertTrue("$id must display $abbreviation", entry.contains("\"$abbreviation\""))
        }
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
        assertTrue(composeUi.contains("app.label.contains(query, ignoreCase = true)"))
        assertTrue(composeUi.contains("app.packageName.contains(query, ignoreCase = true)"))
        assertTrue(composeUi.contains("Icons.Default.Search"))
        assertTrue(composeUi.contains("AppSearchField(searchQuery"))
    }

    @Test fun systemAppFilterLivesInSettingsAndFloatingPanelHasNoFilterOrSearchControl() {
        val activity = ui("LightningDspVolumeManagerActivity")
        val composeUi = ui("LightningVolumeComposeUi")
        val floatingPanel = composeUi.substringAfter("internal fun LightningVolumeFloatingPanel(")
            .substringBefore("@Composable\nprivate fun AppSearchField")
        val resources = File(app, "src/root/res/values/lightning_volume_manager.xml").readText()
        val systemControlsItem = composeUi.indexOf("item(key = \"system_controls\")")
        val searchItem = composeUi.indexOf("item(key = \"app_search\")")

        assertTrue(activity.contains("setText(R.string.ldsp_vm_hide_system_apps)"))
        assertTrue(activity.contains("preferences.getBoolean(\"hide_system_apps\", false)"))
        assertTrue(activity.contains("putBoolean(\"hide_system_apps\", checked).apply()"))
        assertTrue(resources.contains("ldsp_vm_hide_system_apps"))
        assertTrue(composeUi.contains("key == \"hide_system_apps\" -> hideSystemApps"))
        assertTrue(composeUi.contains("(!hideSystemApps || !it.systemApp)"))
        assertTrue(systemControlsItem >= 0 && systemControlsItem < searchItem)
        assertTrue(composeUi.contains("Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End)"))
        assertFalse(composeUi.contains("AppCatalogFilters"))
        assertFalse(floatingPanel.contains("Icons.Default.Search"))
        assertFalse(floatingPanel.contains("AppSearchField"))
        assertFalse(floatingPanel.contains("Hide system apps"))
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

    @Test fun activeModeControlsUsePrivilegedCommandsWithoutAutomaticallyOpeningModesSettings() {
        val manager = backend()
        val root = File(app, "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeRoot.kt").readText()
        val composeUi = ui("LightningVolumeComposeUi")
        val activity = ui("LightningDspVolumeManagerActivity")
        for (source in listOf(composeUi)) {
            assertTrue(source.contains("LightningDspVolumeManager.setSystemRingerMode("))
            assertTrue(source.contains("LightningDspVolumeManager.setSystemInterruptionFilter("))
            assertFalse(source.contains("ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS"))
        }
        assertFalse(activity.contains("LightningDspVolumeManager.setSystemRingerMode("))
        assertFalse(activity.contains("LightningDspVolumeManager.setSystemInterruptionFilter("))
        assertTrue(manager.contains("SystemVolumeOperation.ringer(nextOperationId(), mode)"))
        assertTrue(manager.contains("SystemVolumeOperation.interruptionFilter(nextOperationId(), filter)"))
        assertTrue(manager.contains("if (activeSession?.sideEffectsAuthorized == true)"))
        assertTrue(root.contains("SystemVolumeOperation.Kind.RINGER ->"))
        assertTrue(root.contains("SystemVolumeOperation.Kind.INTERRUPTION_FILTER ->"))
        assertTrue(root.contains("currentAudio.ringerMode = operation.value"))
        assertTrue(root.contains(".setInterruptionFilter(operation.value)"))
        assertFalse(manager.contains("setNotificationPolicyAccessGranted"))
        assertFalse(manager.contains("enabled_accessibility_services"))
        assertTrue(manager.contains("systemVolumeOperationId = operationId"))
        assertTrue(composeUi.contains("LaunchedEffect(state.systemVolumeOperationId)"))
        assertTrue(composeUi.contains("state.systemVolumeError?.let { message ->"))
    }

    @Test fun settingsOnlyExposeRootAndKeepPanelControlsInTheManager() {
        val settings = ui("LightningDspVolumeManagerActivity").substringAfter("private fun showSettings()")
        for (removed in listOf("ldsp_vm_access_mode", "ldsp_vm_access_backend",
            "ldsp_vm_shizuku_permission", "ldsp_vm_open_panel", "ldsp_vm_accessibility_enable",
            "ldsp_vm_accessibility_disable", "ACTION_ACCESSIBILITY_SETTINGS", "ldsp_vm_show_stream",
            "ldsp_vm_ringer", "ldsp_vm_dnd")) {
            assertFalse("Settings must not expose $removed", settings.contains(removed))
        }
        val manager = backend()
        assertTrue(manager.contains("val accessMode: VolumeAccessMode get() = VolumeAccessMode.ROOT"))
        assertTrue(manager.contains("ROOT, SHIZUKU"))
        assertTrue(manager.contains("fun requestShizukuPermission("))
        assertTrue(manager.contains("putString(\"access_mode\", mode.name)"))
        assertTrue(manager.contains("VolumeShizukuProcess(context, receipt, nonce)"))
        assertTrue(settings.contains("ldsp_vm_grant_overlay"))
        val section = settings.indexOf("setText(R.string.ldsp_vm_floating_theme_section)")
        assertTrue(section > settings.lastIndexOf("controls.addView(MaterialSwitch"))
        assertTrue(section > settings.indexOf("ldsp_vm_grant_overlay"))
        assertTrue(settings.indexOf("controls.addView(floatingThemeButton)") > section)
        assertTrue(settings.contains("floatingThemeButton.setText(theme.labelRes)"))
    }

    @Test fun settingsAndFloatingDspCardShareTheDarkBlueAccentWithoutChangingAppTracks() {
        val theme = File(app, "src/root/res/values/volume_settings_theme.xml").readText()
        assertTrue(theme.contains("<color name=\"ldsp_vm_control_blue\">#347FB6</color>"))
        assertTrue(theme.contains("<item name=\"colorPrimary\">@color/ldsp_vm_control_blue</item>"))
        assertTrue(ui("LightningDspVolumeManagerActivity").contains("R.style.ThemeOverlay_LightningDSP_VolumeSettings"))
        assertTrue(ui("LightningDspVolumeCard").contains("floating -> Color(0xFFA6B6C4) to Color(0xFF347FB6)"))
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
        val refresh = overlay.substringAfter("panel?.takeIf { it.isAttachedToWindow }")
            .substringBefore("if (panel != null) hide()")
        assertTrue(overlay.contains("private const val DISMISS_TIMEOUT_MS = 3_300L"))
        assertTrue(overlay.contains("private const val FADE_OUT_DURATION_MS = 500L"))
        assertTrue(refresh.contains("scheduleDismiss()"))
        assertTrue(overlay.contains("scheduleDismiss(host)"))
        assertTrue(hide.contains("current.animate().alpha(0f).setDuration(FADE_OUT_DURATION_MS)"))
        assertTrue(hide.indexOf("withEndAction") < hide.indexOf("Lifecycle.State.DESTROYED"))
        assertTrue(overlay.contains("handler.postDelayed({ finishDismissal(current, manager, generation) },"))
        assertTrue(overlay.contains("FADE_OUT_DURATION_MS + DISMISS_CLEANUP_GRACE_MS"))
        assertTrue(overlay.contains("dismissalGeneration != generation"))
        assertFalse(hide.contains("translationX"))
        assertFalse(hide.contains("translationY"))
    }

    @Test fun floatingPanelIdleTimerObservesInputAndFocusInsteadOfAnUnconditionalTimeout() {
        val overlay = ui("LightningVolumeOverlay")
        val composeUi = ui("LightningVolumeComposeUi")
        assertTrue(overlay.contains("MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE"))
        assertTrue(overlay.contains("MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL"))
        assertTrue(overlay.contains("VolumeOverlayIdlePolicy.Interaction.HOVER"))
        assertTrue(overlay.contains("AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED"))
        assertTrue(overlay.contains("idlePolicy?.remainingMillis(SystemClock.uptimeMillis()) == 0L"))
        assertTrue(overlay.contains("handler.postDelayed(dismiss, delay)"))
        assertTrue(overlay.contains("idlePolicy !== idle"))
        assertTrue(overlay.contains("beginDismissal(explicit = true)"))
        assertTrue(overlay.contains("active && isDismissing && idle.mayResumeOnInteraction()"))
        assertTrue(composeUi.contains("onPanelFocusChanged(it.hasFocus)"))
        assertTrue(composeUi.contains(".focusGroup()"))
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
