package lightning.dsp.volumemanager.dspcard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import lightning.dsp.R
import lightning.dsp.activity.MainActivity
import lightning.dsp.interop.PostGainRangePolicy
import lightning.dsp.interop.PreferenceCache
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import java.util.Locale
import kotlin.math.roundToInt

/** Global DSP controls shared by the mixer and its overlay, not per-app DSP instances. */
class LightningDspCardView(context: Context) : LinearLayout(context),
    SharedPreferences.OnSharedPreferenceChangeListener {
    /** Lets the floating host dismiss itself before opening the full DSP editor. */
    var onOpenEffect: (() -> Unit)? = null

    private data class Effect(val id: String, val namespace: String, val key: Int, val title: Int)
    private data class Parameter(val namespace: String, val key: Int, val title: Int,
        val default: Float, val minimum: Float, val maximum: Float)

    private val effects = listOf(
        Effect("compander", Constants.PREF_COMPANDER, R.string.key_compander_enable, R.string.compander_enable),
        Effect("bass", Constants.PREF_BASS, R.string.key_bass_enable, R.string.bass_enable),
        Effect("equalizer", Constants.PREF_EQ, R.string.key_eq_enable, R.string.eq_enable),
        Effect("graphic_eq", Constants.PREF_GEQ, R.string.key_geq_enable, R.string.geq_enable),
        Effect("reverb", Constants.PREF_REVERB, R.string.key_reverb_enable, R.string.reverb_enable),
        Effect("stereo_wide", Constants.PREF_STEREOWIDE, R.string.key_stereowide_enable, R.string.stereowide_enable),
        Effect("crossfeed", Constants.PREF_CROSSFEED, R.string.key_crossfeed_enable, R.string.crossfeed_enable),
        Effect("tube", Constants.PREF_TUBE, R.string.key_tube_enable, R.string.tube_enable),
        Effect("ddc", Constants.PREF_DDC, R.string.key_ddc_enable, R.string.ddc_enable),
        Effect("liveprog", Constants.PREF_LIVEPROG, R.string.key_liveprog_enable, R.string.liveprog_enable),
        Effect("convolver", Constants.PREF_CONVOLVER, R.string.key_convolver_enable, R.string.convolver_enable)
    )
    private val parameters = mapOf(
        "bass" to Parameter(Constants.PREF_BASS, R.string.key_bass_max_gain, R.string.bass_max_gain, 5f, 3f, 15f),
        "stereo_wide" to Parameter(Constants.PREF_STEREOWIDE, R.string.key_stereowide_mode, R.string.stereowide_level, 60f, 30f, 75f),
        "tube" to Parameter(Constants.PREF_TUBE, R.string.key_tube_drive, R.string.tube_drive, 2f, -3f, 12f)
    )
    private fun preferences(namespace: String) = PreferenceCache.getPreferences(context, namespace)
    private val observedPreferences = (effects.map { it.namespace } + Constants.PREF_APP + Constants.PREF_OUTPUT)
        .distinct().map(::preferences)
    private val power = MaterialSwitch(context)
    private val valueLabel = TextView(context)
    private val gain = Slider(context)
    private val chips = ChipGroup(context)
    private val effectChips = mutableMapOf<Effect, Chip>()
    private var selectedParameter: Parameter? = null
    private var rendering = false
    private var dragging = false
    private var pendingWrite: (() -> Unit)? = null
    private val writeTask = Runnable { flushWrite() }
    private val refreshTask = Runnable { render() }

    init {
        orientation = VERTICAL
        val padding = (12 * resources.displayMetrics.density).roundToInt()
        setPadding(padding, padding, padding, padding)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = context.getString(R.string.ldsp_dsp_card_title)
            minHeight = (44 * resources.displayMetrics.density).roundToInt()
            setIconResource(R.drawable.ic_lightning_volume_mixer)
            iconGravity = MaterialButton.ICON_GRAVITY_START
            iconSize = (28 * resources.displayMetrics.density).roundToInt()
            iconPadding = (8 * resources.displayMetrics.density).roundToInt()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                chips.visibility = if (chips.visibility == VISIBLE) GONE else VISIBLE
            }
        }, LayoutParams(0, -2, 1f))
        power.contentDescription = context.getString(R.string.power_button_alt)
        header.addView(power)
        addView(header)
        valueLabel.apply {
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = context.getString(R.string.output_control_postgain)
            setOnClickListener { flushWrite(); selectedParameter = null; render() }
        }
        val gainRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(valueLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.42f))
            addView(gain, LayoutParams(0, (48 * resources.displayMetrics.density).roundToInt(), 0.58f))
        }
        addView(gainRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        chips.visibility = GONE
        chips.isSingleLine = true
        effects.forEach { effect ->
            val chip = Chip(context).apply {
                setText(effect.title)
                isCheckable = true
                setOnCheckedChangeListener { _, checked ->
                    if (!rendering) {
                        preferences(effect.namespace).edit().putBoolean(context.getString(effect.key), checked).apply()
                        notifyEngine()
                    }
                }
                setOnLongClickListener {
                    flushWrite()
                    val parameter = parameters[effect.id]
                    if (parameter != null) { selectedParameter = parameter; render() }
                    else {
                        onOpenEffect?.invoke()
                        context.startActivity(Intent(context, MainActivity::class.java)
                        .setAction("${context.packageName}.action.OPEN_EFFECT")
                        .putExtra("effect_id", effect.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                    true
                }
            }
            effectChips[effect] = chip
            chips.addView(chip)
        }
        addView(HorizontalScrollView(context).apply { addView(chips) })
        power.setOnCheckedChangeListener { _, checked ->
            if (!rendering) {
                preferences(Constants.PREF_APP).edit().putBoolean(context.getString(R.string.key_powered_on), checked).apply()
                notifyEngine()
            }
        }
        gain.addOnChangeListener { _, value, user ->
            if (user) {
                updateValueLabel(value)
                val parameter = selectedParameter
                pendingWrite = {
                    val output = preferences(Constants.PREF_OUTPUT)
                    val extended = output.getBoolean(context.getString(R.string.key_output_extra_postgain), false)
                    val safe = if (parameter == null) PostGainRangePolicy.clamp(value, extended)
                        else if (value.isFinite()) value.coerceIn(parameter.minimum, parameter.maximum) else parameter.default
                    preferences(parameter?.namespace ?: Constants.PREF_OUTPUT).edit()
                        .putFloat(context.getString(parameter?.key ?: R.string.key_output_postgain), safe).apply()
                    notifyEngine()
                }
                removeCallbacks(writeTask)
                postDelayed(writeTask, 140)
            }
        }
        gain.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { dragging = true }
            override fun onStopTrackingTouch(slider: Slider) { dragging = false; flushWrite(); render() }
        })
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedPreferences.forEach { it.registerOnSharedPreferenceChangeListener(this) }
        render()
    }

    override fun onDetachedFromWindow() {
        flushWrite()
        observedPreferences.forEach { it.unregisterOnSharedPreferenceChangeListener(this) }
        removeCallbacks(refreshTask)
        dragging = false
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        removeCallbacks(refreshTask)
        post(refreshTask)
    }

    private fun flushWrite() {
        removeCallbacks(writeTask)
        val write = pendingWrite
        pendingWrite = null
        write?.invoke()
    }

    private fun notifyEngine() {
        context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    private fun render() {
        if (dragging || pendingWrite != null) return
        rendering = true
        power.isChecked = preferences(Constants.PREF_APP).getBoolean(context.getString(R.string.key_powered_on), true)
        effectChips.forEach { (effect, chip) ->
            chip.isChecked = preferences(effect.namespace).getBoolean(context.getString(effect.key), false)
        }
        val parameter = selectedParameter
        val output = preferences(Constants.PREF_OUTPUT)
        val limit = PostGainRangePolicy.limit(output.getBoolean(context.getString(R.string.key_output_extra_postgain), false))
        // Move through a safe intermediate interval when switching between differently bounded parameters.
        gain.value = gain.valueFrom
        gain.valueFrom = minOf(gain.valueFrom, parameter?.minimum ?: -limit)
        gain.valueTo = maxOf(gain.valueTo, parameter?.maximum ?: limit)
        gain.value = parameter?.minimum ?: -limit
        gain.valueFrom = parameter?.minimum ?: -limit
        gain.valueTo = parameter?.maximum ?: limit
        gain.stepSize = 0f
        val raw = preferences(parameter?.namespace ?: Constants.PREF_OUTPUT)
            .getFloat(context.getString(parameter?.key ?: R.string.key_output_postgain), parameter?.default ?: 0f)
        gain.value = if (raw.isFinite()) raw.coerceIn(gain.valueFrom, gain.valueTo) else parameter?.default ?: 0f
        gain.contentDescription = context.getString(parameter?.title ?: R.string.output_control_postgain)
        updateValueLabel(gain.value)
        rendering = false
    }

    private fun updateValueLabel(value: Float) {
        val parameter = selectedParameter
        val valueText = if (parameter?.key == R.string.key_stereowide_mode)
            String.format(Locale.getDefault(), "%.1f", value)
        else String.format(Locale.getDefault(), "%+.1f dB", value)
        valueLabel.text = context.getString(parameter?.title ?: R.string.output_control_postgain) + " · " + valueText
    }
}
