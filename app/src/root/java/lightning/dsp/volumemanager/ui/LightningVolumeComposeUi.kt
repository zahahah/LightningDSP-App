package lightning.dsp.volumemanager.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lightning.dsp.volumemanager.backend.AppVolume
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import lightning.dsp.volumemanager.backend.VolumeManagerState
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

private val LightningBlue = Color(0xFF0277BD)
private val SystemVolumeBaseTrack = Color(0xFFB0BEC5)
private val SystemVolumeProgressTrack = Color(0xFFEEF1F3)
private val SystemVolumeContent = Color(0xFF263238)

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
    var showAll by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val panelPrefs = remember(context) { context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE) }
    var showDspCard by remember { mutableStateOf(panelPrefs.getBoolean("show_dsp_card", true)) }
    var hideSystemApps by remember { mutableStateOf(panelPrefs.getBoolean("hide_system_apps", false)) }
    var preferenceGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(panelPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when {
                key == "show_dsp_card" -> showDspCard = panelPrefs.getBoolean("show_dsp_card", true)
                key == "hide_system_apps" -> hideSystemApps = panelPrefs.getBoolean("hide_system_apps", false)
                key?.startsWith("hidden_") == true || key?.startsWith("stream_") == true -> preferenceGeneration++
            }
        }
        panelPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { panelPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
    } else if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    @Suppress("UNUSED_VARIABLE") val preferenceState = preferenceGeneration
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
                    actions = {
                        if (!compact) {
                            IconButton(onClick = {
                                searchExpanded = !searchExpanded
                                if (!searchExpanded) searchQuery = ""
                            }) {
                                Icon(if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (searchExpanded) "Close app search" else "Search apps")
                            }
                            IconButton(onClick = { showAll = !showAll }) {
                                Icon(if (showAll) Icons.Default.Check else Icons.Default.Tune,
                                    contentDescription = if (showAll) "Show active apps" else "Show all apps")
                            }
                            IconButton(onClick = onShowOverlay) {
                                Icon(Icons.Default.GraphicEq, contentDescription = "Open floating mixer")
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
            containerColor = MaterialTheme.colorScheme.surface
        ) { insets ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!compact) {
                    item(key = "manager_controls") {
                        ManagerStatus(state = state, onEnabledChange = { enabled ->
                            onManagerEnabledChange(enabled)
                        })
                    }
                    item(key = "per_app_switch") {
                        SettingRow("Per-app volume control", "Apply individual gain to active players.",
                            checked = state.perAppEnabled, onCheckedChange = LightningDspVolumeManager::setPerAppEnabled)
                    }
                }
                item(key = "dsp_card") {
                    if (showDspCard && (!compact || LightningDspVolumeManager.isEnabled)) {
                        DspCard(onOpenEffect = onOpenEffect)
                    }
                }
                item(key = "system_controls") {
                    SystemVolumeControls(context, compact, showAll)
                }
                if (!compact && searchExpanded) {
                    item(key = "app_search") {
                        AppSearchField(searchQuery, onQueryChange = { searchQuery = it }, onClose = {
                            searchQuery = ""
                            searchExpanded = false
                        })
                    }
                }
                if (!compact) {
                    item(key = "app_filters") {
                        AppCatalogFilters(hideSystemApps = hideSystemApps, onHideSystemAppsChange = { hidden ->
                            hideSystemApps = hidden
                            panelPrefs.edit().putBoolean("hide_system_apps", hidden).apply()
                        })
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
                val query = searchQuery.trim()
                val catalogApps = state.apps.filter { app ->
                    (!hideSystemApps || !app.systemApp) &&
                        (query.isEmpty() || app.label.contains(query, ignoreCase = true) ||
                            app.packageName.contains(query, ignoreCase = true))
                }.sortedWith(compareByDescending<AppVolume> { it.active }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                val hiddenApps = catalogApps.filter { panelPrefs.getBoolean("hidden_${it.packageName}", false) }
                val visibleApps = catalogApps.filter { !panelPrefs.getBoolean("hidden_${it.packageName}", false) }
                if (query.isNotEmpty()) {
                    if (catalogApps.isEmpty()) {
                        item(key = "no_search_results") {
                            Text("No apps match ‘$query’.", modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(catalogApps, key = { "search:${it.packageName}" }) { app ->
                            AppVolumeTrack(app, showOptions = true)
                        }
                    }
                } else if (!showAll) {
                    val activeApps = visibleApps.filter { it.active }
                    if (activeApps.isEmpty()) {
                        item(key = "no_active_apps") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No active players", style = MaterialTheme.typography.titleMedium)
                                Text("Use the top-right control to show every app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                androidx.compose.material3.Button(onClick = { showAll = true }, modifier = Modifier.padding(top = 8.dp)) {
                                    Text("Show all apps")
                                }
                            }
                        }
                    } else {
                        items(activeApps, key = { it.packageName }) { app -> AppVolumeTrack(app, showOptions = false) }
                    }
                } else {
                        val sections = listOf(
                            "Playing" to visibleApps.filter { it.active },
                            "Inactive players" to visibleApps.filter { !it.active && it.hadPlayer },
                            "Hidden apps" to hiddenApps,
                            "Other apps" to visibleApps.filter { !it.active && !it.hadPlayer && !it.systemApp },
                            "System apps" to visibleApps.filter { it.systemApp && !it.active },
                        )
                        sections.forEach { (title, apps) ->
                            if (apps.isNotEmpty()) {
                                item(key = "section_$title") {
                                    Text(title, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp),
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                items(apps, key = { "${title}:${it.packageName}" }) { app -> AppVolumeTrack(app, showOptions = true) }
                            }
                        }
                }
            }
        }
    }
}

/** Compact centered mixer used by the always-on-top window, matching the reference panel hierarchy. */
@Composable
internal fun LightningVolumeFloatingPanel(
    state: VolumeManagerState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val panelPrefs = remember(context) {
        context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE)
    }
    var showDspCard by remember { mutableStateOf(panelPrefs.getBoolean("show_dsp_card", true)) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var hideSystemApps by remember { mutableStateOf(panelPrefs.getBoolean("hide_system_apps", false)) }
    var panelTheme by remember {
        mutableStateOf(LightningVolumePanelTheme.fromPreference(
            panelPrefs.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
        ))
    }
    DisposableEffect(panelPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "show_dsp_card" -> showDspCard = panelPrefs.getBoolean("show_dsp_card", true)
                "hide_system_apps" -> hideSystemApps = panelPrefs.getBoolean("hide_system_apps", false)
                LightningVolumePanelTheme.PREFERENCE_KEY -> panelTheme = LightningVolumePanelTheme.fromPreference(
                    panelPrefs.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
                )
            }
        }
        panelPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { panelPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = panelTheme.isDark(androidx.compose.foundation.isSystemInDarkTheme())
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    } else if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    val maxWidth = (configuration.screenWidthDp - 32).coerceAtLeast(280).coerceAtMost(420)
    val minWidth = minOf(320, maxWidth)
    val maxHeight = (configuration.screenHeightDp * 0.82f).roundToInt().coerceAtLeast(320)

    MaterialTheme(colorScheme = lightningVolumeColorScheme(scheme, dark)) {
        Surface(
            modifier = Modifier.widthIn(min = minWidth.dp, max = maxWidth.dp),
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
                    item(key = "dsp_card") { DspCard(onOpenEffect = onClose) }
                }
                item(key = "system_controls") {
                    SystemVolumeControls(context, compact = true, showVisibilityOptions = false)
                }
                item(key = "floating_app_filters") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        AppCatalogFilters(hideSystemApps, onHideSystemAppsChange = { hidden ->
                            hideSystemApps = hidden
                            panelPrefs.edit().putBoolean("hide_system_apps", hidden).apply()
                        })
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) searchQuery = ""
                        }) {
                            Icon(if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (searchExpanded) "Close app search" else "Search apps")
                        }
                    }
                }
                if (searchExpanded) {
                    item(key = "floating_app_search") {
                        AppSearchField(searchQuery, onQueryChange = { searchQuery = it }, onClose = {
                            searchQuery = ""
                            searchExpanded = false
                        })
                    }
                }

                val query = searchQuery.trim()
                val activeApps = if (state.enabled && state.perAppEnabled) {
                    state.apps.filter { it.active && !panelPrefs.getBoolean("hidden_${it.packageName}", false) &&
                        (!hideSystemApps || !it.systemApp) && (query.isEmpty() ||
                        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                } else emptyList()
                if (searchExpanded && query.isNotBlank() && activeApps.isEmpty()) {
                    item(key = "floating_no_search_results") {
                        Text("No active app matches ‘$query’.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(activeApps, key = { it.packageName }) { app ->
                    AppVolumeTrack(app, showOptions = false)
                }
            }
        }
    }
}

@Composable
private fun AppCatalogFilters(hideSystemApps: Boolean, onHideSystemAppsChange: (Boolean) -> Unit) {
    FilterChip(
        selected = hideSystemApps,
        onClick = { onHideSystemAppsChange(!hideSystemApps) },
        label = { Text("Hide system apps") }
    )
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
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingRow("Enable volume manager", "Show the floating panel and per-app controls.", state.enabled, onEnabledChange)
            val status = state.systemVolumeError ?: state.error ?: when {
                !state.enabled -> "Disabled"
                state.connected -> "Ready"
                else -> "Connecting…"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DspCard(onOpenEffect: (() -> Unit)?) {
    LightningDspVolumeCard(onOpenEffect)
}

/** The same compact track rows are used on the manager screen and in the floating panel. */
@Composable
private fun SystemVolumeControls(context: Context, compact: Boolean, showVisibilityOptions: Boolean) {
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
            if (key?.startsWith("stream_") == true) streamConfig++
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Media" to AudioManager.STREAM_MUSIC, "Call" to AudioManager.STREAM_VOICE_CALL,
            "Ring" to AudioManager.STREAM_RING, "Alarm" to AudioManager.STREAM_ALARM,
            "Notifications" to AudioManager.STREAM_NOTIFICATION).forEach { (label, stream) ->
            val selected = preferences.getBoolean("stream_$stream", stream == AudioManager.STREAM_MUSIC)
            if (selected || showVisibilityOptions) {
                var value by remember(stream, volumeGeneration) { mutableFloatStateOf(audio.getStreamVolume(stream).toFloat()) }
                val minimum = if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
                val maximum = audio.getStreamMaxVolume(stream).coerceAtLeast(minimum + 1)
                TrackSurface(value = value, range = minimum.toFloat()..maximum.toFloat(), enabled = !audio.isVolumeFixed,
                    label = label, valueLabel = "${value.roundToInt()}/$maximum", streamIcon = when (stream) {
                        AudioManager.STREAM_MUSIC -> Icons.Default.MusicNote
                        AudioManager.STREAM_RING -> Icons.AutoMirrored.Filled.VolumeUp
                        AudioManager.STREAM_VOICE_CALL -> Icons.Default.Phone
                        AudioManager.STREAM_ALARM -> Icons.Default.Alarm
                        else -> Icons.Default.Notifications
                    },
                    streamIconTint = SystemVolumeContent,
                    baseTrackColor = SystemVolumeBaseTrack,
                    progressFillColor = SystemVolumeProgressTrack,
                    trackContentColor = SystemVolumeContent,
                    progressContentColor = SystemVolumeContent,
                    onValue = {
                        value = it
                        if (!LightningDspVolumeManager.setSystemStreamVolume(stream, it.roundToInt())) {
                            value = audio.getStreamVolume(stream).toFloat()
                        }
                    }, trailing = {
                        if (stream == AudioManager.STREAM_RING) {
                            RingerModeButton(audio, ringerMode) { ringerMode = it }
                            DoNotDisturbButton(context, notifications, dndFilter) { dndFilter = it }
                        }
                        if (showVisibilityOptions) {
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
        }
    }
}

@Composable
private fun RingerModeButton(audio: AudioManager, mode: Int, onModeChanged: (Int) -> Unit) {
    val context = LocalContext.current
    val next = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
        AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
        else -> AudioManager.RINGER_MODE_NORMAL
    }
    androidx.compose.material3.IconButton(onClick = {
        try {
            audio.ringerMode = next
            onModeChanged(audio.ringerMode)
        } catch (_: SecurityException) {
            LightningVolumeOverlay.hide()
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
private fun DoNotDisturbButton(
    context: Context,
    notifications: android.app.NotificationManager,
    currentFilter: Int,
    onFilterChanged: (Int) -> Unit,
) {
    androidx.compose.material3.IconButton(onClick = {
        if (!notifications.isNotificationPolicyAccessGranted) {
            LightningVolumeOverlay.hide()
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else try {
            notifications.setInterruptionFilter(
                if (currentFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
            onFilterChanged(notifications.currentInterruptionFilter)
        } catch (_: SecurityException) {
            LightningVolumeOverlay.hide()
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
private fun AppVolumeTrack(app: AppVolume, showOptions: Boolean) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE) }
    var value by remember(app.packageName, app.volume) { mutableFloatStateOf(app.volume) }
    var hidden by remember(app.packageName) { mutableStateOf(prefs.getBoolean("hidden_${app.packageName}", false)) }
    var volumeKeysIgnored by remember(app.packageName) { mutableStateOf(app.volumeButtonsIgnored) }
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
    TrackSurface(value = value, range = 0f..1f, enabled = controllable,
        label = app.label, valueLabel = "${(value * ceiling * 100).roundToInt()}/${(ceiling * 100).roundToInt()}",
        icon = appIcon, muted = app.muted, onValue = {
            value = it.coerceIn(0f, 1f)
            LightningDspVolumeManager.setAppVolume(app.packageName, value)
        }, baseTrackColor = SystemVolumeBaseTrack, progressFillColor = SystemVolumeProgressTrack,
        trackContentColor = SystemVolumeContent, progressContentColor = SystemVolumeContent, trailing = {
            if (showOptions) {
                VolumeManagerIconToggle(
                    checked = !hidden,
                    checkedDescription = "Hide ${app.label}",
                    checkedIcon = Icons.Default.Visibility,
                    uncheckedDescription = "Show ${app.label}",
                    uncheckedIcon = Icons.Default.VisibilityOff,
                    onCheckedChange = { visible ->
                        hidden = !visible
                        prefs.edit().putBoolean("hidden_${app.packageName}", hidden).apply()
                    }
                )
                VolumeManagerIconToggle(
                    checked = !volumeKeysIgnored,
                    checkedDescription = "Ignore hardware volume buttons for ${app.label}",
                    checkedIcon = Icons.AutoMirrored.Filled.VolumeUp,
                    uncheckedDescription = "Allow hardware volume buttons for ${app.label}",
                    uncheckedIcon = Icons.AutoMirrored.Filled.VolumeOff,
                    onCheckedChange = { allowKeys ->
                        volumeKeysIgnored = !allowKeys
                        LightningDspVolumeManager.setAppVolumeButtonsIgnored(app.packageName, volumeKeysIgnored)
                    }
                )
                AppOptions(app, controllable, hidden = hidden, onHiddenChange = { nextHidden ->
                    hidden = nextHidden
                    prefs.edit().putBoolean("hidden_${app.packageName}", nextHidden).apply()
                })
            }
        })
}

@Composable
private fun AppOptions(
    app: AppVolume,
    controllable: Boolean,
    hidden: Boolean,
    onHiddenChange: (Boolean) -> Unit,
) {
    var menu by remember(app.packageName) { mutableStateOf(false) }
    var details by remember(app.packageName) { mutableStateOf(false) }
    androidx.compose.material3.IconButton(onClick = { menu = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "Options for ${app.label}")
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text(if (hidden) "Show app" else "Hide app") },
            onClick = {
                onHiddenChange(!hidden)
                menu = false
            })
        DropdownMenuItem(text = { Text("More controls") }, onClick = { menu = false; details = true })
    }
    if (details) {
        var muted by remember(app.packageName) { mutableStateOf(app.muted) }
        var ignored by remember(app.packageName) { mutableStateOf(app.volumeButtonsIgnored) }
        var pan by remember(app.packageName) { mutableFloatStateOf(app.pan) }
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mute", Modifier.weight(1f))
                        Switch(checked = muted, enabled = controllable, onCheckedChange = {
                            muted = it; LightningDspVolumeManager.setAppMuted(app.packageName, it)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ignore volume buttons", Modifier.weight(1f))
                        Switch(checked = ignored, onCheckedChange = {
                            ignored = it; LightningDspVolumeManager.setAppVolumeButtonsIgnored(app.packageName, it)
                        })
                    }
                    Text("Stereo balance  ${if (pan < 0) "L ${(-pan * 100).roundToInt()}%" else if (pan > 0) "R ${(pan * 100).roundToInt()}%" else "Center"}")
                    androidx.compose.material3.Slider(value = pan, enabled = controllable,
                        onValueChange = { pan = it; LightningDspVolumeManager.setAppPan(app.packageName, it) },
                        valueRange = -1f..1f)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { details = false }) { Text("Done") } },
            dismissButton = { androidx.compose.material3.TextButton(enabled = controllable, onClick = {
                LightningDspVolumeManager.setAppVolume(app.packageName, 1f)
                LightningDspVolumeManager.setAppMuted(app.packageName, false)
                LightningDspVolumeManager.setAppPan(app.packageName, 0f)
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
    muted: Boolean = false,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    baseTrackColor: Color? = null,
    progressFillColor: Color? = null,
    trackContentColor: Color? = null,
    progressContentColor: Color? = null,
    trailing: @Composable () -> Unit = {},
) {
    val trackColor = baseTrackColor ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val fillColor = progressFillColor ?: if (enabled) LightningBlue else MaterialTheme.colorScheme.outlineVariant
    val latestValue = rememberUpdatedState(value)
    val latestOnValue = rememberUpdatedState(onValue)
    val fraction = ((value - range.start) / (range.endInclusive - range.start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(20.dp))
                .background(trackColor)
                .trackGesture(latestValue, range, enabled, latestOnValue)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range.start, range.endInclusive), range)
                    setProgress { target -> latestOnValue.value(target.coerceIn(range.start, range.endInclusive)); true }
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
                    streamIconTint)
            }
            if (fraction > 0f) {
                Row(
                    Modifier.fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .drawWithContent {
                            clipRect(right = size.width * fraction) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        .clearAndSetSemantics { },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrackContent(icon, streamIcon, label, valueLabel, muted,
                        if (enabled) progressContentColor ?: MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface, streamIconTint)
                }
            }
        }
        trailing()
    }
}

@Composable
private fun RowScope.TrackContent(
    icon: Drawable?,
    streamIcon: ImageVector?,
    label: String,
    valueLabel: String,
    muted: Boolean,
    contentColor: Color,
    streamIconTint: Color?,
) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides contentColor) {
        if (icon != null) {
            val bitmap = remember(icon) { runCatching { icon.toBitmap(48, 48).asImageBitmap() }.getOrNull() }
            if (bitmap != null) Image(bitmap, contentDescription = "$label icon", modifier = Modifier.size(32.dp),
                colorFilter = if (muted) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null)
            else Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(32.dp))
        } else {
            Icon(streamIcon ?: Icons.Default.GraphicEq, contentDescription = label,
                modifier = Modifier.size(32.dp), tint = streamIconTint ?: contentColor)
        }
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
    onValue: State<(Float) -> Unit>,
): Modifier = pointerInput(range, enabled) {
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
            if (next.isConsumed && !dragged) allowTap = false
            if (!dragged && abs(dx) > viewConfiguration.touchSlop && abs(dx) >= abs(dy)) dragged = true
            if (dragged) {
                next.consume()
                onValue.value((startValue + dx / size.width.coerceAtLeast(1) * (range.endInclusive - range.start)).coerceIn(range.start, range.endInclusive))
            }
            last = next
        }
        if (!dragged && allowTap && size.width > 0) onValue.value(range.start + (startX / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    }
}
