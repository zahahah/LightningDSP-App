package lightning.dsp.volumemanager.ui

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lightning.dsp.R
import lightning.dsp.activity.MainActivity
import lightning.dsp.interop.PostGainRangePolicy
import lightning.dsp.interop.PreferenceCache
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.Locale

private val DspBlue = Color(0xFF4997D0)
private val DspBlueOn = Color(0xFF002B43)

private data class DspParameter(
    val namespace: String,
    val key: Int,
    val title: Int,
    val default: Float,
    val minimum: Float,
    val maximum: Float,
    val shortTitle: String,
)

private data class DspEffect(
    val id: String,
    val namespace: String,
    val enabledKey: Int,
    val title: Int,
    val shortTitle: String,
    val parameter: DspParameter? = null,
)

private val bassParameter = DspParameter(
    Constants.PREF_BASS, R.string.key_bass_max_gain, R.string.bass_max_gain, 5f, 3f, 15f, "Bass gain"
)
private val stereoParameter = DspParameter(
    Constants.PREF_STEREOWIDE, R.string.key_stereowide_mode, R.string.stereowide_level, 60f, 30f, 75f, "Stereo width"
)
private val tubeParameter = DspParameter(
    Constants.PREF_TUBE, R.string.key_tube_drive, R.string.tube_drive, 2f, -3f, 12f, "Tube drive"
)

private val dspEffects = listOf(
    DspEffect("compander", Constants.PREF_COMPANDER, R.string.key_compander_enable, R.string.compander_enable, "DRC"),
    DspEffect("bass", Constants.PREF_BASS, R.string.key_bass_enable, R.string.ldsp_vm_effect_bass, "BASS", bassParameter),
    DspEffect("equalizer", Constants.PREF_EQ, R.string.key_eq_enable, R.string.eq_enable, "MEQ"),
    DspEffect("graphic_eq", Constants.PREF_GEQ, R.string.key_geq_enable, R.string.geq_enable, "AREQ"),
    DspEffect("reverb", Constants.PREF_REVERB, R.string.key_reverb_enable, R.string.ldsp_vm_effect_reverb, "REV"),
    DspEffect("stereo_wide", Constants.PREF_STEREOWIDE, R.string.key_stereowide_enable, R.string.ldsp_vm_effect_stereo, "ST", stereoParameter),
    DspEffect("crossfeed", Constants.PREF_CROSSFEED, R.string.key_crossfeed_enable, R.string.crossfeed_enable, "CF"),
    DspEffect("tube", Constants.PREF_TUBE, R.string.key_tube_enable, R.string.tube_enable, "AM", tubeParameter),
    DspEffect("ddc", Constants.PREF_DDC, R.string.key_ddc_enable, R.string.ddc_enable, "DDC"),
    DspEffect("liveprog", Constants.PREF_LIVEPROG, R.string.key_liveprog_enable, R.string.liveprog_enable, "DSP"),
    DspEffect("convolver", Constants.PREF_CONVOLVER, R.string.key_convolver_enable, R.string.convolver_enable, "CONV"),
)

/** Compact LightningDSP control card, styled after the reference's circular DSP controls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LightningDspVolumeCard(onOpenEffect: (() -> Unit)?, floating: Boolean = false, floatingLightTheme: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appPreferences = remember(context) { PreferenceCache.getPreferences(context, Constants.PREF_APP) }
    val outputPreferences = remember(context) { PreferenceCache.getPreferences(context, Constants.PREF_OUTPUT) }
    val effectPreferences = remember(context) {
        dspEffects.map { it.namespace }.distinct().associateWith { PreferenceCache.getPreferences(context, it) }
    }
    val observedPreferences = remember(appPreferences, outputPreferences, effectPreferences) {
        (effectPreferences.values + appPreferences + outputPreferences).distinct()
    }
    var preferenceRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(observedPreferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> preferenceRevision++ }
        observedPreferences.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        onDispose { observedPreferences.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }
    @Suppress("UNUSED_VARIABLE") val currentPreferenceRevision = preferenceRevision

    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedParameter by remember { mutableStateOf<DspParameter?>(null) }
    var pendingValue by remember { mutableStateOf<Float?>(null) }
    var pendingWrite by remember { mutableStateOf<Job?>(null) }
    val extraPostGain = outputPreferences.getBoolean(context.getString(R.string.key_output_extra_postgain), false)
    val postGainLimit = PostGainRangePolicy.limit(extraPostGain)
    val parameter = selectedParameter
    val range = parameter?.let { it.minimum..it.maximum } ?: -postGainLimit..postGainLimit
    val valuePreferences = parameter?.let { effectPreferences.getValue(it.namespace) } ?: outputPreferences
    val valueKey = context.getString(parameter?.key ?: R.string.key_output_postgain)
    val rawValue = valuePreferences.getFloat(valueKey, parameter?.default ?: 0f)
    val initialValue = if (rawValue.isFinite()) rawValue.coerceIn(range.start, range.endInclusive)
        else parameter?.default ?: 0f
    var sliderValue by remember(preferenceRevision, parameter) { mutableFloatStateOf(initialValue) }

    fun commitValue(value: Float) {
        val safeValue = if (value.isFinite()) value.coerceIn(range.start, range.endInclusive) else parameter?.default ?: 0f
        valuePreferences.edit().putFloat(valueKey, safeValue).apply()
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    fun queueValue(value: Float) {
        val safeValue = if (value.isFinite()) value.coerceIn(range.start, range.endInclusive) else parameter?.default ?: 0f
        sliderValue = safeValue
        pendingValue = safeValue
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(140)
            pendingValue = null
            commitValue(safeValue)
        }
    }

    val latestPendingValue = rememberUpdatedState(pendingValue)
    val latestCommit = rememberUpdatedState<(Float) -> Unit> { commitValue(it) }
    DisposableEffect(context) {
        onDispose {
            pendingWrite?.cancel()
            latestPendingValue.value?.let(latestCommit.value)
        }
    }

    fun notifyEngine() = context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    fun openEffect(effect: DspEffect) {
        onOpenEffect?.invoke()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction("${context.packageName}.action.OPEN_EFFECT")
                .putExtra("effect_id", effect.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    val powered = appPreferences.getBoolean(context.getString(R.string.key_powered_on), true)
    val buttonFill = if (powered) DspBlue else MaterialTheme.colorScheme.outlineVariant
    val buttonContent = if (powered) DspBlueOn else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(start = if (floating) 0.dp else 14.dp, end = if (floating) 0.dp else 14.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(buttonFill)
                        .combinedClickable(
                            onClick = { selectedParameter = null; expanded = !expanded },
                            onLongClick = { selectedParameter = null; expanded = true },
                        )
                        .semantics {
                            contentDescription = "LightningDSP effects"
                            role = Role.Button
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lightning_volume_mixer),
                        contentDescription = null,
                        tint = buttonContent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                TrackSurface(
                    value = sliderValue,
                    range = range,
                    enabled = true,
                    label = parameter?.shortTitle ?: context.getString(R.string.output_control_postgain),
                    valueLabel = if (parameter?.key == R.string.key_stereowide_mode)
                        String.format(Locale.getDefault(), "%.1f", sliderValue)
                    else String.format(Locale.getDefault(), "%+.1f dB", sliderValue),
                    onValue = ::queueValue,
                    modifier = Modifier.weight(1f),
                    baseTrackColor = when {
                        floatingLightTheme -> FloatingLightBaseTrack
                        floating -> Color(0xFFA6B6C4)
                        else -> DspBlue.copy(alpha = 0.22f)
                    },
                    progressFillColor = when {
                        floatingLightTheme -> FloatingLightProgressTrack
                        floating -> Color(0xFF347FB6)
                        else -> DspBlue
                    },
                    trackContentColor = if (floatingLightTheme) FloatingIceWhite else null,
                    progressContentColor = if (floatingLightTheme) FloatingIceWhite else DspBlueOn,
                )
            }

            if (expanded) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DspRoundControl(
                        shortTitle = null,
                        title = "Power",
                        checked = powered,
                        power = true,
                        onClick = {
                            appPreferences.edit().putBoolean(context.getString(R.string.key_powered_on), !powered).apply()
                            notifyEngine()
                        }
                    )
                    dspEffects.forEach { effect ->
                        val prefs = effectPreferences.getValue(effect.namespace)
                        val checked = prefs.getBoolean(context.getString(effect.enabledKey), false)
                        DspRoundControl(
                            shortTitle = effect.shortTitle,
                            title = context.getString(effect.title),
                            checked = checked,
                            onClick = {
                                prefs.edit().putBoolean(context.getString(effect.enabledKey), !checked).apply()
                                notifyEngine()
                            },
                            onLongClick = {
                                val control = effect.parameter
                                if (control != null) {
                                    selectedParameter = control
                                } else {
                                    openEffect(effect)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DspRoundControl(
    shortTitle: String?,
    title: String,
    checked: Boolean,
    power: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = if (checked) DspBlue else DspBlue.copy(alpha = 0.24f)
    val content = if (checked) DspBlueOn else inactiveColor
    Column(
        modifier = Modifier.width(58.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape).background(fill)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics {
                    contentDescription = title
                    role = Role.Switch
                    stateDescription = if (checked) "On" else "Off"
                },
            contentAlignment = Alignment.Center
        ) {
            if (power) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            } else {
                Text(shortTitle.orEmpty(), color = content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            color = inactiveColor
        )
    }
}
