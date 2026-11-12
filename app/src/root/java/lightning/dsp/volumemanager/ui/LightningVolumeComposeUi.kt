package lightning.dsp.volumemanager.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import lightning.dsp.view.FloatingToggleButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import lightning.dsp.R
import lightning.dsp.interop.PreferenceCache
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import lightning.dsp.volumemanager.backend.AppVolume
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import lightning.dsp.volumemanager.backend.VolumeManagerState
import lightning.dsp.MainApplication
import lightning.dsp.volumemanager.backend.SHOW_MEDIA_CONTROLS_KEY
import lightning.dsp.volumemanager.backend.SessionDspButtonVisibilityPolicy
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

private val LightningBlue = Color(0xFF0277BD)
private val LightAppDspEnabled = Color(0xFFC7E3F7)
private val LightAppDspEnabledIcon = Color(0xFF456278)
private val SystemVolumeBaseTrack = Color(0xFF8D969C)
private val SystemVolumeProgressTrack = Color(0xFFD1D5D8)
private val SystemVolumeContent = Color(0xFF263238)
private val AppVolumeContent = Color(0xFF101820)
internal val FloatingLightProgressTrack = Color(0xFF4B535B)
internal val FloatingLightBaseTrack = Color(0xFF727C85)
internal val FloatingIceWhite = Color(0xFFF0F5F7)

private fun lightningVolumeColorScheme(base: ColorScheme, dark: Boolean): ColorScheme = if (dark) {
    base.copy(
        primary = Color(0xFF64B5F6), onPrimary = Color(0xFF08283A),
        primaryContainer = Color(0xFF174A70), onPrimaryContainer = Color.White,
        surface = Color(0xFF101A22), onSurface = Color(0xFFEAF2F7),
        surfaceVariant = Color(0xFF22343F), onSurfaceVariant = Color(0xFFBECCD5),
        surfaceContainerLow = Color(0xFF15232C), surfaceContainer = Color(0xFF192832),
        surfaceContainerHigh = Color(0xFF1F313C), surfaceContainerHighest = Color(0xFF293D49),
        outlineVariant = Color(0xFF607887)
    )
} else {
    base.copy(
        primary = LightningBlue, onPrimary = Color.White,
        primaryContainer = Color(0xFF174A70), onPrimaryContainer = Color.White,
        surface = Color(0xFFF6FAFD), onSurface = Color(0xFF17232B),
        surfaceVariant = Color(0xFFE8EFF3), onSurfaceVariant = Color(0xFF455A64),
        surfaceContainerLow = Color(0xFFF0F6FA), surfaceContainer = Color(0xFFEAF2F7),
        surfaceContainerHigh = Color(0xFFE3EDF3), surfaceContainerHighest = Color(0xFFD6E2E9),
        outlineVariant = Color(0xFF78909C)
    )
}

/** The integrated mixer follows the reference app's compact track rows and one-tap active/all view. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LightningVolumeManagerScreen(
    state: VolumeManagerState,
    compact: Boolean,
    onSettings: () -> Unit,
    onShowOverlay: () -> Unit,
    onClose: () -> Unit = {},
    onManagerEnabledChange: (Boolean) -> Unit,
    onOpenEffect: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var inactiveExpanded by rememberSaveable { mutableStateOf(false) }
    var editSnapshot by remember { mutableStateOf<VolumeManagerEditSnapshot?>(null) }
    var editedAppSettings by remember { mutableStateOf<Map<String, AppEditSettings>>(emptyMap()) }
    val listState = rememberLazyListState()
    val activeBounds = remember { VolumeReorderBounds() }
    val recentBounds = remember { VolumeReorderBounds() }
    val inactiveBounds = remember { VolumeReorderBounds() }
    var viewport by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val panelPrefs = remember(context) { context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE) }
    val audioPrefs = remember(context) { context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE) }
    val savedAppOrder by rememberAppVolumeOrder(panelPrefs)
    var showDspCard by remember { mutableStateOf(panelPrefs.getBoolean("show_dsp_card", true)) }
    var hideSystemApps by remember { mutableStateOf(panelPrefs.getBoolean("hide_system_apps", false)) }
    var showMediaControls by remember { mutableStateOf(panelPrefs.getBoolean(SHOW_MEDIA_CONTROLS_KEY, false)) }
    var showSessionDspControls by remember { mutableStateOf(panelPrefs.getBoolean("show_session_dsp_controls", false)) }
    var alwaysShowSessionDspControls by remember { mutableStateOf(panelPrefs.getBoolean("always_show_session_dsp_controls", true)) }
    var appDspButtonsRevealed by rememberSaveable { mutableStateOf(false) }
    var sessionMode by remember { mutableStateOf(!(context.applicationContext as MainApplication).isGlobalMode) }
    var hiddenAppIds by remember(panelPrefs) { mutableStateOf(readFloatingHiddenAppIds(panelPrefs)) }
    var managerHiddenAppIds by remember(panelPrefs) { mutableStateOf(readManagerHiddenAppIds(panelPrefs)) }
    DisposableEffect(panelPrefs, audioPrefs) {
        migrateLegacyManagerHiddenApps(panelPrefs)
        managerHiddenAppIds = readManagerHiddenAppIds(panelPrefs)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(FLOATING_HIDDEN_PREFIX)) hiddenAppIds = readFloatingHiddenAppIds(panelPrefs)
            if (key == null || key.startsWith(MANAGER_HIDDEN_PREFIX)) managerHiddenAppIds = readManagerHiddenAppIds(panelPrefs)
            if (key == "show_dsp_card") showDspCard = panelPrefs.getBoolean("show_dsp_card", true)
            if (key == "hide_system_apps") hideSystemApps = panelPrefs.getBoolean("hide_system_apps", false)
            if (key == SHOW_MEDIA_CONTROLS_KEY) showMediaControls = panelPrefs.getBoolean(SHOW_MEDIA_CONTROLS_KEY, false)
            if (key == "show_session_dsp_controls") showSessionDspControls = panelPrefs.getBoolean("show_session_dsp_controls", false)
            if (key == "always_show_session_dsp_controls") alwaysShowSessionDspControls = panelPrefs.getBoolean("always_show_session_dsp_controls", true)
            sessionMode = !(context.applicationContext as MainApplication).isGlobalMode
        }
        val audioModeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == context.getString(R.string.key_audioformat_processing))
                sessionMode = !(context.applicationContext as MainApplication).isGlobalMode
        }
        panelPrefs.registerOnSharedPreferenceChangeListener(listener)
        audioPrefs.registerOnSharedPreferenceChangeListener(audioModeListener)
        onDispose {
            panelPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            audioPrefs.unregisterOnSharedPreferenceChangeListener(audioModeListener)
        }
    }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
    } else if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    // Resolve outside LazyListScope: changing an order must invalidate its item provider,
    // even when no player status, search query, or volume changed.
    val allApps = state.apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    val allAppIds = allApps.map { it.packageName }
    val appOrder = savedAppOrder.include(allAppIds)
    val appById = allApps.associateBy { it.packageName }
    val activeIds = allApps.filter { it.active }.map { it.packageName }.toSet()
    val query = searchQuery.trim()
    val filteredIds = allApps.filter { app ->
        (!hideSystemApps || !app.systemApp) &&
            (query.isEmpty() || app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true))
    }.map { it.packageName }
    val catalogApps = appOrder.displayOrder(filteredIds).mapNotNull(appById::get)
    val hiddenApps = catalogApps.filter { it.packageName in managerHiddenAppIds }
    val visibleApps = catalogApps.filterNot { it.packageName in managerHiddenAppIds }
    val activeApps = visibleApps.filter { it.active }
    val recentIds = state.recentPackages.toSet()
    val recentApps = visibleApps.filter { !it.active && it.packageName in recentIds }
    val inactiveApps = visibleApps.filterNot { it.active || it.packageName in recentIds }
    fun saveMove(apps: List<AppVolume>, from: String, to: String) {
        val current = AppVolumeOrderPreferences.read(panelPrefs).include(allAppIds)
        val moved = current.move(apps.map { it.packageName }, activeIds, from, to)
        if (moved != current) AppVolumeOrderPreferences.save(panelPrefs, moved)
    }
    fun setPinned(app: String, enabled: Boolean) {
        val current = AppVolumeOrderPreferences.read(panelPrefs).include(allAppIds)
        AppVolumeOrderPreferences.save(panelPrefs, current.withPinned(app, enabled))
    }
    fun beginEditing() {
        editSnapshot = VolumeManagerEditSnapshot(
            order = AppVolumeOrderPreferences.read(panelPrefs).include(allAppIds),
            showDspCard = panelPrefs.getBoolean("show_dsp_card", true),
            hiddenFromPanel = readFloatingHiddenAppIds(panelPrefs),
            hiddenFromManager = readManagerHiddenAppIds(panelPrefs),
            appSettings = state.apps.associate { it.packageName to it.toEditSettings() },
        )
        editedAppSettings = emptyMap()
        editing = true
        showAll = true
        searchQuery = ""
        searchExpanded = false
    }
    fun cancelEditing() {
        val snapshot = editSnapshot ?: return
        AppVolumeOrderPreferences.save(panelPrefs, snapshot.order)
        val editor = panelPrefs.edit().putBoolean("show_dsp_card", snapshot.showDspCard)
        restorePackageFlags(editor, panelPrefs, FLOATING_HIDDEN_PREFIX, snapshot.hiddenFromPanel)
        restorePackageFlags(editor, panelPrefs, MANAGER_HIDDEN_PREFIX, snapshot.hiddenFromManager)
        editor.apply()
        editedAppSettings.forEach { (packageName, _) ->
            snapshot.appSettings[packageName]?.let { original ->
                LightningDspVolumeManager.setAppVolume(packageName, original.volume)
                LightningDspVolumeManager.setAppMuted(packageName, original.muted)
                LightningDspVolumeManager.setAppPan(packageName, original.pan)
                LightningDspVolumeManager.setAppVolumeButtonsIgnored(packageName, original.volumeButtonsIgnored)
            }
        }
        editedAppSettings = emptyMap()
        editSnapshot = null
        editing = false
    }
    fun recordEditedSettings(packageName: String, settings: AppEditSettings) {
        val original = editSnapshot?.appSettings?.get(packageName) ?: return
        editedAppSettings = if (settings == original) editedAppSettings - packageName
            else editedAppSettings + (packageName to settings)
    }
    val editDirty = editSnapshot?.let { snapshot ->
        snapshot.order != AppVolumeOrderPreferences.read(panelPrefs).include(allAppIds) ||
            snapshot.showDspCard != showDspCard ||
            snapshot.hiddenFromPanel != hiddenAppIds ||
            snapshot.hiddenFromManager != managerHiddenAppIds || editedAppSettings.isNotEmpty()
    } == true
    val showAppDspButtons = SessionDspButtonVisibilityPolicy.shouldShow(
        sessionMode, showSessionDspControls, alwaysShowSessionDspControls, appDspButtonsRevealed
    )
    MaterialTheme(colorScheme = lightningVolumeColorScheme(scheme, dark)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "LightningDSP Volume Manager",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp)
                        )
                    },
                    navigationIcon = {
                        if (!compact) IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to LightningDSP")
                        }
                    },
                    actions = {
                        if (!compact) {
                            if (editing && editDirty) IconButton(onClick = { cancelEditing() }) {
                                Icon(Icons.Default.Close, contentDescription = "Discard editing changes")
                            }
                            IconButton(onClick = {
                                if (editing) {
                                    editedAppSettings = emptyMap()
                                    editSnapshot = null
                                    editing = false
                                } else beginEditing()
                            }) {
                                Icon(if (editing) Icons.Default.Check else Icons.Default.Tune,
                                    contentDescription = if (editing) "Finish editing volume controls" else "Edit volume controls")
                            }
                            IconButton(onClick = onSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "Volume Manager settings")
                            }
                        } else {
                            IconButton(onClick = onClose) {
                                Icon(Icons.Default.Close, contentDescription = "Close floating mixer")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            },
            bottomBar = {
                if (!compact) VolumeManagerFooter(state, onShowOverlay)
            },
            containerColor = MaterialTheme.colorScheme.surface
        ) { insets ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(insets).onGloballyPositioned {
                    viewport = it.boundsInRoot()
                    activeBounds.viewport = viewport
                    inactiveBounds.viewport = viewport
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!compact) {
                    item(key = "manager_controls") {
                        ManagerStatus(state = state, onEnabledChange = onManagerEnabledChange)
                    }
                    if (state.enabled && !Settings.canDrawOverlays(context)) {
                        item(key = "overlay_permission") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.ldsp_vm_overlay_permission),
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onShowOverlay) { Text("Allow") }
                            }
                        }
                    }
                }
                if (!compact) item(key = "manager_dsp_card_spacing") {
                    Spacer(Modifier.height(8.dp))
                }
                item(key = "dsp_card") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (showDspCard && (!compact || LightningDspVolumeManager.isEnabled)) {
                                DspCard(onOpenEffect = onOpenEffect,
                                    onDspIconClick = { appDspButtonsRevealed = !appDspButtonsRevealed })
                            } else if (editing) {
                                Text("LightningDSP", modifier = Modifier.padding(14.dp))
                            }
                        }
                        if (editing && !compact) {
                            VolumeManagerIconToggle(
                                checked = showDspCard,
                                checkedDescription = "Hide LightningDSP card",
                                checkedIcon = Icons.Default.Visibility,
                                uncheckedDescription = "Show LightningDSP card",
                                uncheckedIcon = Icons.Default.VisibilityOff,
                                onCheckedChange = { panelPrefs.edit().putBoolean("show_dsp_card", it).apply() }
                            )
                        }
                    }
                }
                item(key = "system_controls") {
                    SystemVolumeControls(context, compact, showAll || editing,
                        editing = editing,
                        listState = listState, viewport = viewport)
                }
                if (!compact && !editing) {
                    item(key = "app_search") {
                        if (searchExpanded) {
                            AppSearchField(searchQuery, onQueryChange = { searchQuery = it }, onClose = {
                                searchQuery = ""
                                searchExpanded = false
                            })
                        } else {
                            Row(
                                Modifier.fillMaxWidth().height(56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(onClick = { searchExpanded = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search apps")
                                }
                            }
                        }
                    }
                }
                if (!state.enabled || !state.perAppEnabled) {
                    item(key = "manager_disabled_status") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (state.enabled) "Per-app controls are off" else "Volume Manager is off",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Saved app controls can still be configured here and apply when enabled.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (query.isNotEmpty()) {
                    if (catalogApps.isEmpty()) {
                        item(key = "no_search_results") {
                            Text("No apps match ‘$query’.", modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(catalogApps, key = { "search:${it.packageName}" }) { app ->
                            AppVolumeTrack(app, showOptions = true, pinned = app.packageName in appOrder.pinned,
                                hiddenFromManager = app.packageName in managerHiddenAppIds,
                                onHiddenFromManagerChange = { hidden ->
                                    setPackageFlag(panelPrefs, MANAGER_HIDDEN_PREFIX, app.packageName, hidden)
                                }, initiallyHiddenFromPanel = app.packageName in hiddenAppIds,
                                allowAppDspControls = showAppDspButtons,
                                onEditSettingsChange = if (editing) ::recordEditedSettings else null)
                        }
                    }
                } else {
                    val groups = if (!compact) listOf(
                        Triple("Active", activeApps, activeBounds),
                        Triple("Recent", recentApps, recentBounds),
                    ) else listOf(Triple("Active", activeApps, activeBounds))
                    groups.forEach { (title, apps, bounds) ->
                        if (apps.isNotEmpty()) {
                            item(key = "section_$title") {
                                Text(title, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(apps, key = { "${title}:${it.packageName}" }) { app ->
                                val peers = appOrder.movablePeers(apps.map { it.packageName }, activeIds, app.packageName)
                                VolumeReorderItem(
                                    id = app.packageName, order = peers,
                                    bounds = bounds, listState = listState,
                                    editing = editing && peers.size > 1,
                                    placementModifier = Modifier.animateItem(
                                        placementSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                                    ),
                                    onMove = { from, to -> saveMove(apps, from, to) },
                                ) {
                                    AppVolumeTrack(app, showOptions = showAll || editing, editing = editing,
                                        pinned = app.packageName in appOrder.pinned,
                                        hiddenFromManager = app.packageName in managerHiddenAppIds,
                                        onHiddenFromManagerChange = { hidden ->
                                            setPackageFlag(panelPrefs, MANAGER_HIDDEN_PREFIX, app.packageName, hidden)
                                        },
                                        initiallyHiddenFromPanel = app.packageName in hiddenAppIds,
                                        allowAppDspControls = showAppDspButtons,
                                        onEditSettingsChange = if (editing) ::recordEditedSettings else null,
                                        onPinnedChange = if (editing) { enabled -> setPinned(app.packageName, enabled) } else null)
                                }
                            }
                        }
                    }
                    if (!compact && inactiveApps.isNotEmpty()) {
                        item(key = "section_Inactive") {
                            Row(
                                Modifier.fillMaxWidth().clickable { inactiveExpanded = !inactiveExpanded }
                                    .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Inactive (${inactiveApps.size})", modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(if (inactiveExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (inactiveExpanded) "Collapse inactive apps" else "Expand inactive apps",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (inactiveExpanded) items(inactiveApps, key = { "Inactive:${it.packageName}" }) { app ->
                            val peers = appOrder.movablePeers(inactiveApps.map { it.packageName }, activeIds, app.packageName)
                            VolumeReorderItem(
                                id = app.packageName, order = peers,
                                bounds = inactiveBounds, listState = listState,
                                editing = editing && peers.size > 1,
                                placementModifier = Modifier.animateItem(
                                    placementSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                                ),
                                onMove = { from, to -> saveMove(inactiveApps, from, to) },
                            ) {
                                AppVolumeTrack(app, showOptions = showAll || editing, editing = editing,
                                    pinned = app.packageName in appOrder.pinned,
                                    hiddenFromManager = app.packageName in managerHiddenAppIds,
                                    onHiddenFromManagerChange = { hidden ->
                                        setPackageFlag(panelPrefs, MANAGER_HIDDEN_PREFIX, app.packageName, hidden)
                                    },
                                    initiallyHiddenFromPanel = app.packageName in hiddenAppIds,
                                    allowAppDspControls = showAppDspButtons,
                                    onEditSettingsChange = if (editing) ::recordEditedSettings else null,
                                    onPinnedChange = if (editing) { enabled -> setPinned(app.packageName, enabled) } else null)
                            }
                        }
                    }
                    if (activeApps.isEmpty() && compact && !editing) {
                        item(key = "no_active_apps") {
                            Text("No active players. Use the top-right control to show every app.",
                                modifier = Modifier.padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if ((showAll || editing) && hiddenApps.isNotEmpty()) {
                        item(key = "section_hidden") {
                            Text("Hidden apps", style = MaterialTheme.typography.titleSmall)
                        }
                        items(hiddenApps, key = { "hidden:${it.packageName}" }) { app ->
                            AppVolumeTrack(app, showOptions = true, editing = editing,
                                pinned = app.packageName in appOrder.pinned,
                                hiddenFromManager = true,
                                onHiddenFromManagerChange = { hidden ->
                                    setPackageFlag(panelPrefs, MANAGER_HIDDEN_PREFIX, app.packageName, hidden)
                                },
                                initiallyHiddenFromPanel = app.packageName in hiddenAppIds,
                                allowAppDspControls = showAppDspButtons,
                                onEditSettingsChange = if (editing) ::recordEditedSettings else null,
                                onPinnedChange = if (editing) { enabled -> setPinned(app.packageName, enabled) } else null)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VolumeProfilePicker(state: VolumeManagerState, expanded: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<lightning.dsp.volumemanager.backend.VolumeProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (expanded) Popup(
        popupPositionProvider = VolumeProfilePopupPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp) {
        Column(Modifier.widthIn(min = 176.dp, max = 220.dp)
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.6f)
            .verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        state.profiles.forEach { profile ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .combinedClickable(
                        onClick = {
                            onDismiss()
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { LightningDspVolumeManager.selectProfile(profile.id).get() } }
                                    .onFailure { error = it.cause?.message ?: it.message ?: "Could not select profile" }
                            }
                        },
                        onLongClickLabel = "Rename ${profile.name}",
                        onLongClick = { onDismiss(); newName = profile.name; renaming = profile }
                    ).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (profile.id == state.selectedProfileId) Icon(Icons.Default.Check, null)
                else Spacer(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenuItem(text = { Text("New profile") }, leadingIcon = { Icon(Icons.Default.Add, null) },
            onClick = { onDismiss(); newName = ""; creating = true })
        }
        }
    }
    if (creating || renaming != null) AlertDialog(
        onDismissRequest = { creating = false; renaming = null },
        title = { Text(if (renaming != null) "Rename volume profile" else "Create volume profile") },
        text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Profile name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val profile = renaming
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    if (profile == null) LightningDspVolumeManager.createProfile(newName).get()
                    else LightningDspVolumeManager.renameProfile(profile.id, newName).get()
                } }
                    .onSuccess { creating = false; renaming = null }
                    .onFailure {
                        creating = false; renaming = null
                        error = it.cause?.message ?: it.message ?: "Could not save profile"
                    }
            }
        }) { Text(if (renaming != null) "Save" else "Create") } },
        dismissButton = { TextButton(onClick = { creating = false; renaming = null }) { Text("Cancel") } },
    )
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Profile error") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

/** Keep the menu flush with the top of its footer button, even near the screen's bottom inset. */
internal object VolumeProfilePopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                   layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** Compact centered mixer used by the always-on-top window, matching the reference panel hierarchy. */
@Composable
internal fun LightningVolumeFloatingPanel(
    state: VolumeManagerState,
    onClose: () -> Unit,
    onPanelFocusChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    androidx.compose.runtime.LaunchedEffect(state.systemVolumeOperationId) {
        if (state.systemVolumeOperationId > 0L) {
            state.systemVolumeError?.let { message ->
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val panelPrefs = remember(context) {
        context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE)
    }
    val audioPrefs = remember(context) { context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE) }
    val savedAppOrder by rememberAppVolumeOrder(panelPrefs)
    var showDspCard by remember { mutableStateOf(panelPrefs.getBoolean("show_dsp_card", true)) }
    var showMediaButtons by remember { mutableStateOf(panelPrefs.getBoolean(SHOW_MEDIA_CONTROLS_KEY, false)) }
    var showSessionDspButtons by remember { mutableStateOf(panelPrefs.getBoolean("show_session_dsp_controls", false)) }
    var alwaysShowSessionDspButtons by remember { mutableStateOf(panelPrefs.getBoolean("always_show_session_dsp_controls", true)) }
    var appDspButtonsRevealed by rememberSaveable { mutableStateOf(false) }
    var sessionMode by remember { mutableStateOf(!(context.applicationContext as MainApplication).isGlobalMode) }
    var hiddenAppIds by remember(panelPrefs) { mutableStateOf(readFloatingHiddenAppIds(panelPrefs)) }
    var panelTheme by remember {
        mutableStateOf(LightningVolumePanelTheme.fromPreference(
            panelPrefs.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
        ))
    }
    DisposableEffect(panelPrefs, audioPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(FLOATING_HIDDEN_PREFIX)) hiddenAppIds = readFloatingHiddenAppIds(panelPrefs)
            when (key) {
                "show_dsp_card" -> showDspCard = panelPrefs.getBoolean("show_dsp_card", true)
                SHOW_MEDIA_CONTROLS_KEY -> showMediaButtons = panelPrefs.getBoolean(SHOW_MEDIA_CONTROLS_KEY, false)
                "show_session_dsp_controls" -> showSessionDspButtons = panelPrefs.getBoolean("show_session_dsp_controls", false)
                "always_show_session_dsp_controls" -> alwaysShowSessionDspButtons = panelPrefs.getBoolean("always_show_session_dsp_controls", true)
                LightningVolumePanelTheme.PREFERENCE_KEY -> panelTheme = LightningVolumePanelTheme.fromPreference(
                    panelPrefs.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
                )
            }
        }
        val audioModeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == context.getString(R.string.key_audioformat_processing))
                sessionMode = !(context.applicationContext as MainApplication).isGlobalMode
        }
        panelPrefs.registerOnSharedPreferenceChangeListener(listener)
        audioPrefs.registerOnSharedPreferenceChangeListener(audioModeListener)
        onDispose {
            panelPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            audioPrefs.unregisterOnSharedPreferenceChangeListener(audioModeListener)
        }
    }
    val allApps = state.apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    val appOrder = savedAppOrder.include(allApps.map { it.packageName })
    val appById = allApps.associateBy { it.packageName }
    val activeIds = if (state.enabled && state.perAppEnabled) {
        allApps.filter { (it.active || (showMediaButtons && it.retainedPausedMedia)) && it.packageName !in hiddenAppIds }
            .map { it.packageName }
    } else emptyList()
    val activeApps = appOrder.displayOrder(activeIds).mapNotNull(appById::get)
    val showAppDspButtons = SessionDspButtonVisibilityPolicy.shouldShow(
        sessionMode, showSessionDspButtons, alwaysShowSessionDspButtons, appDspButtonsRevealed
    )
    val dark = panelTheme.isDark(androidx.compose.foundation.isSystemInDarkTheme())
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    } else if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    val addedControls = (if (showMediaButtons) 1 else 0) + if (showAppDspButtons) 1 else 0
    val sideClearance = if (addedControls > 0) 48 else 32
    val availableWidth = (configuration.screenWidthDp - sideClearance).coerceAtLeast(280)
    val desiredWidth = 420 + addedControls * 44
    val maxWidth = minOf(availableWidth, desiredWidth)
    val minWidth = minOf(320, maxWidth)
    val maxHeight = (configuration.screenHeightDp * 0.82f).roundToInt().coerceAtLeast(320)

    MaterialTheme(colorScheme = lightningVolumeColorScheme(scheme, dark)) {
        Surface(
            modifier = Modifier.widthIn(min = minWidth.dp, max = maxWidth.dp)
                .onFocusChanged { onPanelFocusChanged(it.hasFocus) }
                .focusGroup(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showDspCard && state.enabled) {
                    item(key = "dsp_card") {
                        DspCard(onOpenEffect = onClose, floating = true, floatingLightTheme = !dark,
                            onDspIconClick = { appDspButtonsRevealed = !appDspButtonsRevealed })
                    }
                }
                item(key = "system_controls") {
                    SystemVolumeControls(context, compact = true, showVisibilityOptions = false, floatingLightTheme = !dark, floatingPanel = true)
                }
                items(activeApps, key = { it.packageName }) { app ->
                    AppVolumeTrack(app, showOptions = false, floatingLightTheme = !dark,
                        pinned = app.packageName in appOrder.pinned,
                        allowAppDspControls = showAppDspButtons)
                }
            }
        }
    }
}

private const val FLOATING_HIDDEN_PREFIX = "hidden_"
private const val MANAGER_HIDDEN_PREFIX = "manager_hidden_"
private const val HIDDEN_MANAGER_MIGRATION_KEY = "hide_manager_semantics_migrated_v1"

private data class VolumeManagerEditSnapshot(
    val order: AppVolumeOrder,
    val showDspCard: Boolean,
    val hiddenFromPanel: Set<String>,
    val hiddenFromManager: Set<String>,
    val appSettings: Map<String, AppEditSettings>,
)

private data class AppEditSettings(
    val volume: Float,
    val muted: Boolean,
    val pan: Float,
    val volumeButtonsIgnored: Boolean,
)

private fun AppVolume.toEditSettings() = AppEditSettings(volume, muted, pan, volumeButtonsIgnored)

private fun readPackageFlags(preferences: android.content.SharedPreferences, prefix: String): Set<String> =
    preferences.all.filter { (key, value) -> key.startsWith(prefix) && value == true }
        .keys.map { it.removePrefix(prefix) }.filter(String::isNotBlank).toSet()

private fun readFloatingHiddenAppIds(preferences: android.content.SharedPreferences): Set<String> =
    readPackageFlags(preferences, FLOATING_HIDDEN_PREFIX)

private fun readManagerHiddenAppIds(preferences: android.content.SharedPreferences): Set<String> =
    readPackageFlags(preferences, MANAGER_HIDDEN_PREFIX)

private fun setPackageFlag(
    preferences: android.content.SharedPreferences, prefix: String, packageName: String, enabled: Boolean,
) {
    val editor = preferences.edit()
    if (enabled) editor.putBoolean(prefix + packageName, true) else editor.remove(prefix + packageName)
    editor.apply()
}

private fun restorePackageFlags(
    editor: android.content.SharedPreferences.Editor,
    preferences: android.content.SharedPreferences,
    prefix: String,
    values: Set<String>,
) {
    preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
    values.forEach { editor.putBoolean(prefix + it, true) }
}

/** Preserve existing Hide choices as manager-hidden while retaining floating-panel visibility. */
private fun migrateLegacyManagerHiddenApps(preferences: android.content.SharedPreferences) {
    if (preferences.getBoolean(HIDDEN_MANAGER_MIGRATION_KEY, false)) return
    val legacyHidden = readFloatingHiddenAppIds(preferences)
    val alreadyHidden = readManagerHiddenAppIds(preferences)
    preferences.edit().apply {
        (legacyHidden - alreadyHidden).forEach { putBoolean(MANAGER_HIDDEN_PREFIX + it, true) }
        putBoolean(HIDDEN_MANAGER_MIGRATION_KEY, true)
    }.apply()
}

@Composable
private fun AppSearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Search apps") },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close app search")
            }
        }
    )
}

@Composable
private fun ManagerStatus(state: VolumeManagerState, onEnabledChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow("Enable volume manager", "Show the floating panel and per-app controls.", state.enabled, onEnabledChange)
            if (state.enabled && (!state.connected || state.error != null || state.systemVolumeError != null)) {
                ManagerStatusMessage(state)
            }
            if (state.enabled && !state.connected && state.error != null) {
                Text("If root access was denied, authorize LightningDSP in KernelSU/Magisk, then retry.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = LightningDspVolumeManager::resume) { Text("Retry connection") }
            }
            SettingRow("Per-app volume control", "Apply individual gain to active players.",
                checked = state.perAppEnabled, onCheckedChange = LightningDspVolumeManager::setPerAppEnabled)
        }
    }
}

@Composable
private fun ManagerStatusMessage(state: VolumeManagerState) {
    val status = state.systemVolumeError ?: state.error ?: when {
        !state.enabled -> "Disabled"
        state.connected -> "Ready"
        else -> "Connecting…"
    }
    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Reuse the home screen's actual Material BottomAppBar and FloatingToggleButton. */
@SuppressLint("InflateParams") // AndroidView supplies the root size; XML child anchor params remain intact.
@Composable
private fun VolumeManagerFooter(state: VolumeManagerState, onShowOverlay: () -> Unit) {
    val context = LocalContext.current
    var profileMenuOpen by remember { mutableStateOf(false) }
    val appPreferences = remember(context) { PreferenceCache.getPreferences(context, Constants.PREF_APP) }
    val powerKey = context.getString(R.string.key_powered_on)
    var dspPowered by remember(appPreferences) { mutableStateOf(appPreferences.getBoolean(powerKey, true)) }
    DisposableEffect(appPreferences, powerKey) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == powerKey) dspPowered = appPreferences.getBoolean(powerKey, true)
        }
        appPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val currentPowered by rememberUpdatedState(dspPowered)
    Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            factory = { context ->
                LayoutInflater.from(context).inflate(R.layout.volume_manager_footer, null).apply {
                    findViewById<com.google.android.material.bottomappbar.BottomAppBar>(R.id.volume_manager_bottom_bar)
                        .apply {
                            elevation = 0f
                        }
                    findViewById<FloatingToggleButton>(R.id.volume_manager_power_toggle).apply {
                        toggleOnClick = false
                        setOnToggleClickListener(object : FloatingToggleButton.OnToggleClickListener {
                            override fun onClick() {
                                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                val next = !currentPowered
                                appPreferences.edit().putBoolean(powerKey, next).apply()
                                dspPowered = next
                                context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                            }
                        })
                    }
                }
            },
            update = { view ->
                view.findViewById<FloatingToggleButton>(R.id.volume_manager_power_toggle).apply {
                    if (isToggled != dspPowered) isToggled = dspPowered
                    contentDescription = if (dspPowered) "Disable LightningDSP effects" else "Enable LightningDSP effects"
                }
            },
        )
        IconButton(onClick = onShowOverlay,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
            Icon(painterResource(R.drawable.ic_volume_graphic_eq), contentDescription = "Open floating panel")
        }
        if (state.perAppEnabled) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                IconButton(onClick = { profileMenuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_volume_profiles), contentDescription = "Volume profiles")
                }
                VolumeProfilePicker(state, profileMenuOpen) { profileMenuOpen = false }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!checked) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DspCard(
    onOpenEffect: (() -> Unit)?,
    floating: Boolean = false,
    floatingLightTheme: Boolean = false,
    onDspIconClick: (() -> Unit)? = null,
) {
    LightningDspVolumeCard(onOpenEffect, floating, floatingLightTheme, onDspIconClick)
}

/** The same compact track rows are used on the manager screen and in the floating panel. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SystemVolumeControls(
    context: Context, compact: Boolean, showVisibilityOptions: Boolean, floatingLightTheme: Boolean = false,
    floatingPanel: Boolean = false, editing: Boolean = false,
    listState: LazyListState? = null, viewport: androidx.compose.ui.geometry.Rect? = null,
) {
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val notifications = remember(context) { context.getSystemService(android.app.NotificationManager::class.java) }
    val preferences = remember(context) { context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE) }
    var streamConfig by remember { mutableIntStateOf(0) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var volumeGeneration by remember { mutableIntStateOf(0) }
    var ringerMode by remember { mutableIntStateOf(audio.ringerMode) }
    var dndFilter by remember { mutableIntStateOf(notifications.currentInterruptionFilter) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("stream_") || key == "group_system_streams" ||
                key == "order_streams" || key == MediaGroupExpansionPolicy.PREFERENCE_KEY) streamConfig++
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { volumeGeneration++ }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    androidx.compose.runtime.LaunchedEffect(compact) {
        while (true) { delay(1_000); refreshTick++ }
    }
    androidx.compose.runtime.LaunchedEffect(refreshTick) {
        ringerMode = audio.ringerMode
        dndFilter = notifications.currentInterruptionFilter
    }
    @Suppress("UNUSED_VARIABLE") val refreshState = streamConfig + refreshTick
    var secondaryExpanded by rememberSaveable { mutableStateOf(MediaGroupExpansionPolicy.initiallyExpanded(floatingPanel)) }
    val grouping = preferences.getBoolean("group_system_streams", true)
    val streams = listOf("Media" to AudioManager.STREAM_MUSIC, "Call" to AudioManager.STREAM_VOICE_CALL,
        "Ring" to AudioManager.STREAM_RING, "Alarm" to AudioManager.STREAM_ALARM,
        "Notifications" to AudioManager.STREAM_NOTIFICATION)
    val streamIds = VolumeDisplayOrder.resolve(
        preferences.getString("order_streams", "").orEmpty().split('|'),
        streams.map { it.second.toString() })
    val dragBounds = remember { VolumeReorderBounds() }
    dragBounds.viewport = viewport
    val selectedStreamIds = streams.mapNotNull { (_, stream) ->
        stream.toString().takeIf { preferences.getBoolean("stream_$stream", true) }
    }.toSet()
    val visibleStreamIds = VolumeDisplayOrder.visibleSystemStreams(
        order = streamIds,
        selected = selectedStreamIds,
        mediaId = AudioManager.STREAM_MUSIC.toString(),
        grouping = grouping,
        expanded = secondaryExpanded,
        editing = editing,
        showVisibilityOptions = showVisibilityOptions,
    )
    val presented = visibleStreamIds.mapNotNull { id -> streams.find { it.second.toString() == id } }
    val iconTapExpansion = MediaGroupExpansionPolicy.useIconTap(
        floating = floatingPanel,
        enabled = preferences.getBoolean(MediaGroupExpansionPolicy.PREFERENCE_KEY, false),
        grouping = grouping, editing = editing,
    )
    val showExpansionChevron = MediaGroupExpansionPolicy.showChevron(
        grouping, secondaryExpanded, iconTapExpansion,
        mediaVisible = presented.any { it.second == AudioManager.STREAM_MUSIC },
    )
    @Composable
    fun ExpansionButton() {
        IconButton(onClick = { secondaryExpanded = !secondaryExpanded }) {
            Icon(if (secondaryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (secondaryExpanded) "Collapse other volumes" else "Expand other volumes")
        }
    }
    @Composable
    fun StreamRow(label: String, stream: Int) {
        val selected = preferences.getBoolean("stream_$stream", true)
        var value by remember(stream, volumeGeneration) { mutableFloatStateOf(audio.getStreamVolume(stream).toFloat()) }
        val minimum = if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
        val maximum = audio.getStreamMaxVolume(stream).coerceAtLeast(minimum + 1)
        TrackSurface(value = value, range = minimum.toFloat()..maximum.toFloat(), enabled = !audio.isVolumeFixed && !editing,
            label = label, valueLabel = "${value.roundToInt()}/$maximum", streamIcon = when (stream) {
                AudioManager.STREAM_MUSIC -> Icons.Default.MusicNote
                AudioManager.STREAM_RING -> Icons.AutoMirrored.Filled.VolumeUp
                AudioManager.STREAM_VOICE_CALL -> Icons.Default.Phone
                AudioManager.STREAM_ALARM -> Icons.Default.Alarm
                else -> Icons.Default.Notifications
            },
            onIconClick = if (stream == AudioManager.STREAM_MUSIC && iconTapExpansion)
                ({ secondaryExpanded = !secondaryExpanded }) else null,
            streamIconTint = if (floatingLightTheme) FloatingIceWhite else SystemVolumeContent,
            baseTrackColor = if (floatingLightTheme) FloatingLightBaseTrack else SystemVolumeBaseTrack,
            progressFillColor = if (floatingLightTheme) FloatingLightProgressTrack else SystemVolumeProgressTrack,
            trackContentColor = if (floatingLightTheme) FloatingIceWhite else SystemVolumeContent,
            progressContentColor = if (floatingLightTheme) FloatingIceWhite else SystemVolumeContent,
            onValue = {
                value = it
                if (!LightningDspVolumeManager.setSystemStreamVolume(stream, it.roundToInt())) {
                    value = audio.getStreamVolume(stream).toFloat()
                }
            }, trailing = {
                if (stream == AudioManager.STREAM_MUSIC && showExpansionChevron) ExpansionButton()
                if (stream == AudioManager.STREAM_RING) {
                    RingerModeButton(ringerMode)
                    DoNotDisturbButton(dndFilter)
                }
                if (showVisibilityOptions || editing) {
                    VolumeManagerIconToggle(
                        checked = selected,
                        checkedDescription = "Hide $label volume",
                        checkedIcon = Icons.Default.Visibility,
                        uncheckedDescription = "Show $label volume",
                        uncheckedIcon = Icons.Default.VisibilityOff,
                        onCheckedChange = { checked -> preferences.edit().putBoolean("stream_$stream", checked).apply() }
                    )
                }
            })
    }
    Column {
        if (grouping && presented.none { it.second == AudioManager.STREAM_MUSIC }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("System volumes", Modifier.weight(1f))
                ExpansionButton()
            }
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presented.forEach { (label, stream) -> StreamRow(label, stream) }
            }
        } else if (presented.isNotEmpty()) {
            val rowsHeight = 48.dp * presented.size + 8.dp * (presented.size - 1)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(rowsHeight),
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presented, key = { it.second }) { (label, stream) ->
                    VolumeReorderItem(
                        id = stream.toString(), order = presented.map { it.second.toString() },
                        bounds = dragBounds, listState = listState, editing = editing,
                        placementModifier = Modifier.animateItem(
                            placementSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        ),
                        onMove = { from, to ->
                            val moved = VolumeDisplayOrder.move(streamIds, from, to)
                            preferences.edit().putString("order_streams", moved.joinToString("|")).apply()
                        },
                    ) { StreamRow(label, stream) }
                }
            }
        }
    }
}

@Composable
private fun RingerModeButton(mode: Int) {
    val context = LocalContext.current
    val next = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
        AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
        else -> AudioManager.RINGER_MODE_NORMAL
    }
    androidx.compose.material3.IconButton(onClick = {
        if (!LightningDspVolumeManager.setSystemRingerMode(next)) {
            android.widget.Toast.makeText(context, R.string.ldsp_vm_mode_unavailable, android.widget.Toast.LENGTH_LONG).show()
        }
    }) {
        Icon(
            if (mode == AudioManager.RINGER_MODE_VIBRATE) Icons.Default.Vibration
            else if (mode == AudioManager.RINGER_MODE_SILENT) Icons.AutoMirrored.Filled.VolumeOff
            else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Change ringer mode"
        )
    }
}

@Composable
private fun DoNotDisturbButton(currentFilter: Int) {
    val context = LocalContext.current
    androidx.compose.material3.IconButton(onClick = {
        val next = if (currentFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
        else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        if (!LightningDspVolumeManager.setSystemInterruptionFilter(next)) {
            android.widget.Toast.makeText(context, R.string.ldsp_vm_mode_unavailable, android.widget.Toast.LENGTH_LONG).show()
        }
    }) {
        Icon(
            if (currentFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                Icons.Default.NotificationsActive else Icons.Default.DoNotDisturbOn,
            contentDescription = "Toggle do not disturb"
        )
    }
}

@Composable
private fun AppVolumeTrack(
    app: AppVolume, showOptions: Boolean, floatingLightTheme: Boolean = false, editing: Boolean = false,
    allowAppDspControls: Boolean = false,
    pinned: Boolean = false,
    hiddenFromManager: Boolean = false,
    onHiddenFromManagerChange: ((Boolean) -> Unit)? = null,
    initiallyHiddenFromPanel: Boolean? = null,
    onEditSettingsChange: ((String, AppEditSettings) -> Unit)? = null,
    onPinnedChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE) }
    var value by remember(app.packageName, app.volume) { mutableFloatStateOf(app.volume) }
    var hiddenFromPanel by remember(app.packageName, initiallyHiddenFromPanel) {
        mutableStateOf(initiallyHiddenFromPanel ?: prefs.getBoolean("hidden_${app.packageName}", false))
    }
    var muted by remember(app.packageName, app.muted) { mutableStateOf(app.muted) }
    var volumeButtonsIgnored by remember(app.packageName, app.volumeButtonsIgnored) {
        mutableStateOf(app.volumeButtonsIgnored)
    }
    var dspEnabled by remember(app.packageName, app.dspEnabled) { mutableStateOf(app.dspEnabled) }
    val lightControls = floatingLightTheme || MaterialTheme.colorScheme.background.luminance() > 0.5f
    var appIcon by remember(app.packageName) { mutableStateOf<Drawable?>(null) }
    androidx.compose.runtime.LaunchedEffect(app.packageName) {
        appIcon = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        }
    }
    // Saved controls remain editable while a player is inactive or the engine is disabled.
    val controllable = app.controllable
    val ceiling = remember { runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }.getOrDefault(1f) }
    TrackSurface(value = value, range = 0f..1f, enabled = controllable && !editing,
        label = app.label, valueLabel = "${(value * ceiling * 100).roundToInt()}/${(ceiling * 100).roundToInt()}",
        icon = appIcon, muted = app.muted, onValue = {
            value = it.coerceIn(0f, 1f)
            LightningDspVolumeManager.setAppVolume(app.packageName, value)
        }, baseTrackColor = if (floatingLightTheme) FloatingLightBaseTrack else SystemVolumeBaseTrack, progressFillColor = if (floatingLightTheme) FloatingLightProgressTrack else SystemVolumeProgressTrack,
        trackContentColor = if (floatingLightTheme) FloatingIceWhite else AppVolumeContent, progressContentColor = if (floatingLightTheme) FloatingIceWhite else AppVolumeContent,
        leading = {
            if (allowAppDspControls) {
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    dspEnabled = !dspEnabled
                    LightningDspVolumeManager.setAppDspEnabled(app.packageName, dspEnabled)
                }) {
                    val buttonFill = when {
                        !dspEnabled -> MaterialTheme.colorScheme.surfaceVariant
                        lightControls -> LightAppDspEnabled
                        else -> LightningBlue
                    }
                    val buttonBorder = if (!dspEnabled) MaterialTheme.colorScheme.outlineVariant
                        else if (lightControls) LightAppDspEnabledIcon.copy(alpha = 0.55f)
                        else LightningBlue.copy(alpha = 0.8f)
                    Box(Modifier.size(32.dp).clip(CircleShape).background(buttonFill)
                        .border(1.dp, buttonBorder, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PowerSettingsNew,
                            contentDescription = if (dspEnabled) "Disable LightningDSP for ${app.label}" else "Enable LightningDSP for ${app.label}",
                            modifier = Modifier.size(19.dp),
                            tint = if (dspEnabled && lightControls) LightAppDspEnabledIcon
                                else if (dspEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (editing && pinned) Icon(Icons.Default.PushPin,
                contentDescription = stringResource(R.string.ldsp_vm_pinned_app, app.label),
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }, trailing = {
            if (app.mediaControlVisible) {
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    LightningDspVolumeManager.toggleMediaPlayback(app.packageName)
                }) {
                    val mediaFill = if (lightControls) Color.White else MaterialTheme.colorScheme.surfaceVariant
                    Box(Modifier.size(32.dp).clip(CircleShape).background(mediaFill)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(if (app.mediaPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (app.mediaPlaying) "Pause ${app.label}" else "Play ${app.label}",
                            modifier = Modifier.size(21.dp),
                            tint = if (lightControls) Color(0xFF263238) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (showOptions) {
                VolumeManagerIconToggle(
                    checked = !hiddenFromPanel,
                    checkedDescription = "Hide ${app.label} from floating panel",
                    checkedIcon = Icons.Default.Visibility,
                    uncheckedDescription = "Show ${app.label} in floating panel",
                    uncheckedIcon = Icons.Default.VisibilityOff,
                    onCheckedChange = { visible ->
                        hiddenFromPanel = !visible
                        setPackageFlag(prefs, FLOATING_HIDDEN_PREFIX, app.packageName, hiddenFromPanel)
                    }
                )
                VolumeManagerIconToggle(
                    checked = !muted,
                    checkedDescription = "Mute ${app.label}",
                    checkedIcon = Icons.AutoMirrored.Filled.VolumeUp,
                    uncheckedDescription = "Unmute ${app.label}",
                    uncheckedIcon = Icons.AutoMirrored.Filled.VolumeOff,
                    onCheckedChange = { unmuted ->
                        muted = !unmuted
                        LightningDspVolumeManager.setAppMuted(app.packageName, muted)
                        onEditSettingsChange?.invoke(app.packageName,
                            AppEditSettings(value, muted, app.pan, volumeButtonsIgnored))
                    }
                )
                AppOptions(app, controllable, hiddenFromManager = hiddenFromManager,
                    onHiddenFromManagerChange = onHiddenFromManagerChange,
                    pinned = pinned, onPinnedChange = onPinnedChange,
                    initialSettings = AppEditSettings(value, muted, app.pan, volumeButtonsIgnored),
                    onEditSettingsChange = onEditSettingsChange)
            }
        })
}

@Composable
private fun AppOptions(
    app: AppVolume,
    controllable: Boolean,
    hiddenFromManager: Boolean,
    onHiddenFromManagerChange: ((Boolean) -> Unit)?,
    pinned: Boolean,
    onPinnedChange: ((Boolean) -> Unit)?,
    initialSettings: AppEditSettings,
    onEditSettingsChange: ((String, AppEditSettings) -> Unit)?,
) {
    var menu by remember(app.packageName) { mutableStateOf(false) }
    var details by remember(app.packageName) { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { menu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options for ${app.label}")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onPinnedChange != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (pinned) R.string.ldsp_vm_unpin else R.string.ldsp_vm_pin)) },
                    onClick = { onPinnedChange(!pinned); menu = false },
                )
            }
            if (onHiddenFromManagerChange != null) {
                DropdownMenuItem(text = { Text(if (hiddenFromManager) "Show in manager" else "Hide from manager") },
                    onClick = {
                        onHiddenFromManagerChange(!hiddenFromManager)
                        menu = false
                    })
            }
            DropdownMenuItem(text = { Text("More controls") }, onClick = { menu = false; details = true })
        }
    }
    if (details) {
        var muted by remember(app.packageName) { mutableStateOf(initialSettings.muted) }
        var ignored by remember(app.packageName) { mutableStateOf(initialSettings.volumeButtonsIgnored) }
        var pan by remember(app.packageName) { mutableFloatStateOf(initialSettings.pan) }
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mute", Modifier.weight(1f))
                        Switch(checked = muted, enabled = controllable, onCheckedChange = {
                            muted = it; LightningDspVolumeManager.setAppMuted(app.packageName, it)
                            onEditSettingsChange?.invoke(app.packageName, initialSettings.copy(muted = it, pan = pan, volumeButtonsIgnored = ignored))
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ignore volume buttons", Modifier.weight(1f))
                        Switch(checked = ignored, onCheckedChange = {
                            ignored = it; LightningDspVolumeManager.setAppVolumeButtonsIgnored(app.packageName, it)
                            onEditSettingsChange?.invoke(app.packageName, initialSettings.copy(muted = muted, pan = pan, volumeButtonsIgnored = it))
                        })
                    }
                    Text("Stereo balance  ${if (pan < 0) "L ${(-pan * 100).roundToInt()}%" else if (pan > 0) "R ${(pan * 100).roundToInt()}%" else "Center"}")
                    androidx.compose.material3.Slider(value = pan, enabled = controllable,
                        onValueChange = {
                            pan = it
                            LightningDspVolumeManager.setAppPan(app.packageName, it)
                            onEditSettingsChange?.invoke(app.packageName, initialSettings.copy(muted = muted, pan = it, volumeButtonsIgnored = ignored))
                        },
                        valueRange = -1f..1f)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { details = false }) { Text("Done") } },
            dismissButton = { androidx.compose.material3.TextButton(enabled = controllable, onClick = {
                LightningDspVolumeManager.setAppVolume(app.packageName, 1f)
                LightningDspVolumeManager.setAppMuted(app.packageName, false)
                LightningDspVolumeManager.setAppPan(app.packageName, 0f)
                onEditSettingsChange?.invoke(app.packageName,
                    initialSettings.copy(volume = 1f, muted = false, pan = 0f, volumeButtonsIgnored = ignored))
                muted = false; pan = 0f; details = false
            }) { Text("Reset") } }
        )
    }
}

@Composable
internal fun TrackSurface(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    label: String,
    valueLabel: String,
    icon: Drawable? = null,
    streamIcon: ImageVector? = null,
    streamIconTint: Color? = null,
    onIconClick: (() -> Unit)? = null,
    muted: Boolean = false,
    onValue: (Float) -> Unit,
    yieldOnLongPress: Boolean = true,
    onTrackWidthChanged: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    baseTrackColor: Color? = null,
    progressFillColor: Color? = null,
    trackContentColor: Color? = null,
    progressContentColor: Color? = null,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val trackColor = baseTrackColor ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val fillColor = progressFillColor ?: if (enabled) LightningBlue else MaterialTheme.colorScheme.outlineVariant
    val textPaddingPx = with(LocalDensity.current) { 16.dp.toPx() }
    val latestValue = rememberUpdatedState(value)
    val latestOnValue = rememberUpdatedState(onValue)
    val fraction = ((value - range.start) / (range.endInclusive - range.start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        leading()
        BoxWithConstraints(
            modifier = Modifier.weight(1f).height(48.dp)
                .onSizeChanged { onTrackWidthChanged?.invoke(it.width.toFloat()) }
                .clip(RoundedCornerShape(20.dp))
                .background(trackColor)
                .trackGesture(latestValue, range, enabled, latestOnValue, yieldOnLongPress)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range.start, range.endInclusive), range)
                    setProgress { target ->
                        if (enabled) { latestOnValue.value(target.coerceIn(range.start, range.endInclusive)); true }
                        else false
                    }
                }
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 2.dp, bottomEnd = 2.dp))
                        .background(fillColor)
                )
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackContent(icon, streamIcon, label, valueLabel, muted,
                    trackContentColor ?: MaterialTheme.colorScheme.onSurface,
                    streamIconTint, onIconClick)
            }
            if (fraction > 0f) {
                Row(
                    Modifier.fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .drawWithContent {
                            clipRect(right = trackTextClipRight(size.width, textPaddingPx, fraction)) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        .clearAndSetSemantics { },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrackContent(icon, streamIcon, label, valueLabel, muted,
                        progressContentColor ?: if (enabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface, streamIconTint)
                }
            }
        }
        trailing()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.TrackContent(
    icon: Drawable?,
    streamIcon: ImageVector?,
    label: String,
    valueLabel: String,
    muted: Boolean,
    contentColor: Color,
    streamIconTint: Color?,
    onIconClick: (() -> Unit)? = null,
) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides contentColor) {
        if (icon != null) {
            val bitmap = remember(icon) { runCatching { icon.toBitmap(48, 48).asImageBitmap() }.getOrNull() }
            if (bitmap != null) Image(bitmap, contentDescription = "$label icon", modifier = Modifier.size(32.dp),
                colorFilter = if (muted) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null)
            else Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(32.dp))
        } else {
            Icon(streamIcon ?: Icons.Default.GraphicEq, contentDescription = label,
                modifier = Modifier.size(32.dp).then(if (onIconClick != null)
                    Modifier.clickable(onClick = onIconClick, role = Role.Button,
                        onClickLabel = stringResource(R.string.ldsp_vm_toggle_volumes))
                    else Modifier),
                tint = streamIconTint ?: contentColor)
        }
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(valueLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun VolumeManagerIconToggle(
    checked: Boolean,
    checkedDescription: String,
    checkedIcon: ImageVector,
    uncheckedDescription: String,
    uncheckedIcon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    val description = if (checked) checkedDescription else uncheckedDescription
    IconButton(onClick = { onCheckedChange(!checked) }) {
        Icon(if (checked) checkedIcon else uncheckedIcon, contentDescription = description)
    }
}

private fun Modifier.trackGesture(
    current: State<Float>, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onValue: State<(Float) -> Unit>, yieldOnLongPress: Boolean,
): Modifier = pointerInput(range, enabled, yieldOnLongPress) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startX = down.position.x
        val startY = down.position.y
        val startValue = current.value
        var last = down
        var dragged = false
        var allowTap = !down.isConsumed
        while (last.pressed) {
            val next = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            val dx = next.position.x - startX
            val dy = next.position.y - startY
            if (!dragged && VolumeTrackGesturePolicy.shouldYield(
                    next.isConsumed, next.uptimeMillis - down.uptimeMillis, dx, dy,
                    viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis,
                    yieldOnLongPress)) {
                allowTap = false
                break
            }
            if (!dragged && abs(dx) > viewConfiguration.touchSlop && abs(dx) >= abs(dy)) dragged = true
            if (dragged) {
                next.consume()
                onValue.value((startValue + dx / size.width.coerceAtLeast(1) * (range.endInclusive - range.start)).coerceIn(range.start, range.endInclusive))
            }
            last = next
        }
        if (!dragged && allowTap && last.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis && size.width > 0) onValue.value(range.start + (startX / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    }
}

/** Text is drawn inside padded content, while the fill fraction spans the entire track. */
internal fun trackTextClipRight(contentWidth: Float, padding: Float, fraction: Float): Float =
    ((contentWidth + padding * 2f) * fraction.coerceIn(0f, 1f) - padding).coerceIn(0f, contentWidth)
