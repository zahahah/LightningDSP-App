package lightning.dsp.volumemanager.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import lightning.dsp.MainApplication
import lightning.dsp.R
import lightning.dsp.interop.PreferenceCache
import lightning.dsp.model.root.RemoteEffectSession
import lightning.dsp.utils.Constants
import lightning.dsp.volumemanager.dspcard.LightningDspCardView
import lightning.dsp.volumemanager.backend.AppVolume
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import lightning.dsp.volumemanager.backend.VolumeManagerState
import kotlin.math.roundToInt

/** Views shared by the full mixer and optional floating panel, with no audio IPC on the UI thread. */
internal class LightningVolumePanel(context: Context, private val compact: Boolean) : LinearLayout(context) {
    companion object {
        const val PREFERENCES = "lightningdsp_volume_panel"
        private val activityWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "LightningDSP-card-status").apply { isDaemon = true }
        }
    }
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val controls = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    }
    private val dspCard = LightningDspCardView(context)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "show_dsp_card" || key == "hide_inactive_dsp_card") updateCardVisibility()
        if (key?.startsWith("stream_") == true) refreshStreams()
        if (key?.startsWith("hidden_") == true) updateApps()
    }
    private val status = label(14f)
    private val enabledSwitch = MaterialSwitch(context)
    private val streams = LinearLayout(context).apply { orientation = VERTICAL }
    private val appNavigation = LinearLayout(context).apply { orientation = VERTICAL }
    private val empty = label(14f).apply { setText(R.string.ldsp_vm_empty) }
    private val adapter = AppAdapter()
    private var snapshot: VolumeManagerState? = null
    private var query = ""
    private var group = if (compact) R.string.ldsp_vm_group_active else R.string.ldsp_vm_group_all
    private var rendering = false
    private val interaction = VolumePanelInteractionPolicy()
    private var streamVolumes = emptyList<Int>()
    private var streamMode = -1
    private var engineAvailable = false
    private var audioActive = false
    private var checkingActivity = false
    private var activityGeneration = 0
    private val refreshCard = object : Runnable {
        override fun run() {
            refreshDspActivity()
            if (streamMode != audio.mode) refreshStreams()
            postDelayed(this, 1_000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (!compact) {
            enabledSwitch.setText(R.string.ldsp_vm_enable)
            enabledSwitch.setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    LightningDspVolumeManager.setEnabled(checked)
                    // One explicit preflight; permission alone is not evidence an overlay can attach.
                    if (checked && Settings.canDrawOverlays(context)) LightningVolumeOverlay.show(context)
                }
            }
            controls.addView(enabledSwitch, LayoutParams(-1, -2))
            controls.addView(label(14f).apply { setText(R.string.ldsp_vm_description) })
        }
        controls.addView(status)
        if (compact) dspCard.onOpenEffect = { LightningVolumeOverlay.hide() }
        controls.addView(dspCard, LayoutParams(-1, -2))
        updateCardVisibility()
        controls.addView(streams)
        controls.addView(quickControls())
        refreshStreams()
        if (!compact) {
            appNavigation.addView(AppCompatEditText(context).apply {
                setSingleLine()
                setHint(R.string.ldsp_vm_search)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        query = s?.toString().orEmpty()
                        updateApps()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }, LayoutParams(-1, -2))
            val groups = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
            listOf(R.string.ldsp_vm_group_active, R.string.ldsp_vm_group_inactive,
                R.string.ldsp_vm_group_other, R.string.ldsp_vm_group_saved,
                R.string.ldsp_vm_group_all, R.string.ldsp_vm_group_hidden,
                R.string.ldsp_vm_group_system).forEach { name ->
                groups.addView(Chip(context).apply {
                    id = View.generateViewId()
                    setText(name)
                    isCheckable = true
                    isChecked = name == group
                    setOnCheckedChangeListener { _, checked -> if (checked) { group = name; updateApps() } }
                })
            }
            appNavigation.addView(groups)
        }
        controls.addView(appNavigation)
        controls.addView(empty)
        // A single scroll owner keeps the header and app list reachable even on short displays.
        addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ConcatAdapter(ControlsAdapter(), this@LightningVolumePanel.adapter)
            itemAnimator = null
        }, LayoutParams(-1, -1))
    }

    private inner class ControlsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(controls) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        updateCardVisibility()
        post(refreshCard)
    }

    override fun onDetachedFromWindow() {
        interaction.finishAppDrag()
        interaction.finishSystemDrag()
        activityGeneration++
        removeCallbacks(refreshCard)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDetachedFromWindow()
    }

    private fun updateCardVisibility() {
        val visibility = VolumePanelVisibilityPolicy.evaluate(snapshot?.enabled == true,
            snapshot?.perAppEnabled == true, preferences.getBoolean("show_dsp_card", true),
            preferences.getBoolean("hide_inactive_dsp_card", false), engineAvailable, audioActive)
        dspCard.visibility = if (visibility.showDspCard) VISIBLE else GONE
    }

    private fun refreshDspActivity() {
        if (checkingActivity) return
        val app = context.applicationContext as? MainApplication
        val effects = runCatching {
            app?.rootSessionDatabase?.sessionList?.values?.toList()?.mapNotNull {
                (it as? RemoteEffectSession)?.effect?.effect
            }.orEmpty()
        }.getOrDefault(emptyList())
        val powered = PreferenceCache.getPreferences(context, Constants.PREF_APP)
            .getBoolean(context.getString(R.string.key_powered_on), true)
        val generation = activityGeneration
        checkingActivity = true
        activityWorker.execute {
            // AudioEffect.enabled can perform Binder IPC. Never query it on the UI thread.
            val available = powered && effects.any { runCatching { it.enabled }.getOrDefault(false) }
            val active = runCatching { audio.isMusicActive }.getOrDefault(false)
            post {
                checkingActivity = false
                if (generation == activityGeneration && isAttachedToWindow) {
                    engineAvailable = available
                    audioActive = active
                    updateCardVisibility()
                }
            }
        }
    }

    fun render(state: VolumeManagerState) {
        snapshot = state
        rendering = true
        enabledSwitch.isChecked = state.enabled
        updateCardVisibility()
        status.text = state.error ?: context.getString(when {
            !state.enabled -> R.string.ldsp_vm_off
            state.connected -> R.string.ldsp_vm_ready
            else -> R.string.ldsp_vm_pending
        })
        val currentVolumes = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION)
            .map { audio.getStreamVolume(it) }
        if (currentVolumes != streamVolumes) refreshStreams()
        if (interaction.shouldRenderApps(state.enabled, state.perAppEnabled)) updateApps()
        rendering = false
    }

    fun refreshStreams() {
        if (!interaction.requestStreamRefresh()) return
        streamMode = audio.mode
        streams.removeAllViews()
        streamVolumes = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION)
            .map { audio.getStreamVolume(it) }
        listOf(R.string.ldsp_vm_media to AudioManager.STREAM_MUSIC,
            R.string.ldsp_vm_ring to AudioManager.STREAM_RING,
            R.string.ldsp_vm_call to AudioManager.STREAM_VOICE_CALL,
            R.string.ldsp_vm_alarm to AudioManager.STREAM_ALARM,
            R.string.ldsp_vm_notification to AudioManager.STREAM_NOTIFICATION).forEach { (name, stream) ->
            val selected = preferences.getBoolean("stream_$stream", stream == AudioManager.STREAM_MUSIC)
            if (!selected) return@forEach
            val maximum = audio.getStreamMaxVolume(stream)
            if (maximum <= 0) return@forEach
            val minimum = if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
            if (minimum >= maximum) return@forEach
            val title = label(14f).apply { text = context.getString(name) }
            streams.addView(title)
            streams.addView(slider(maximum).apply {
                valueFrom = minimum.toFloat()
                value = audio.getStreamVolume(stream).coerceIn(minimum, maximum).toFloat()
                contentDescription = context.getString(name)
                isEnabled = !audio.isVolumeFixed
                trackDragging(this, systemStream = true)
                addOnChangeListener { _, value, user -> if (user) {
                    LightningDspVolumeManager.setSystemStreamVolume(stream, value.roundToInt())
                } }
            })
        }
    }

    private fun quickControls() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.ldsp_vm_ringer)
            setOnClickListener {
                val next = when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                try { audio.ringerMode = next }
                catch (_: SecurityException) { openNotificationPolicy() }
                text = context.getString(when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> R.string.ldsp_vm_sound
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.ldsp_vm_vibrate
                    else -> R.string.ldsp_vm_silent
                })
                refreshStreams()
            }
        }, LayoutParams(0, -2, 1f))
        addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.ldsp_vm_dnd)
            setOnClickListener {
                val notifications = context.getSystemService(NotificationManager::class.java)
                if (!notifications.isNotificationPolicyAccessGranted) openNotificationPolicy()
                else try {
                    notifications.setInterruptionFilter(if (notifications.currentInterruptionFilter ==
                        NotificationManager.INTERRUPTION_FILTER_ALL) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        else NotificationManager.INTERRUPTION_FILTER_ALL)
                } catch (_: SecurityException) { openNotificationPolicy() }
            }
        }, LayoutParams(0, -2, 1f))
    }

    private fun openNotificationPolicy() {
        if (compact) LightningVolumeOverlay.hide()
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun updateApps() {
        val state = snapshot ?: return
        val showApps = VolumePanelVisibilityPolicy.evaluate(state.enabled, state.perAppEnabled,
            false, false, false, false).showApps
        appNavigation.visibility = if (showApps) VISIBLE else GONE
        if (!showApps) {
            adapter.items = emptyList()
            adapter.notifyDataSetChanged()
            empty.visibility = GONE
            return
        }
        adapter.items = state.apps.filter { app ->
            val hidden = preferences.getBoolean("hidden_${app.packageName}", false)
            val groupMatch = when (group) {
                R.string.ldsp_vm_group_active -> app.active && !hidden
                R.string.ldsp_vm_group_inactive -> !app.active && !hidden && app.hadPlayer
                R.string.ldsp_vm_group_other -> !app.active && !hidden && !app.hadPlayer
                R.string.ldsp_vm_group_saved -> (app.volume != 1f || app.muted || app.pan != 0f) && !hidden
                R.string.ldsp_vm_group_hidden -> hidden
                R.string.ldsp_vm_group_system -> app.systemApp && !hidden
                else -> !hidden
            }
            groupMatch && (app.label.contains(query, true) || app.packageName.contains(query, true))
        }.sortedWith(compareByDescending<AppVolume> { it.active }.thenBy { it.label.lowercase() })
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.items.isEmpty()) VISIBLE else GONE
    }

    private fun canChangeAppAudio(app: AppVolume): Boolean = snapshot?.let {
        VolumePanelVisibilityPolicy.canChangeAppAudio(app.controllable, it.enabled, it.connected, it.perAppEnabled)
    } == true

    private fun showOptions(app: AppVolume) {
        if (compact) {
            LightningVolumeOverlay.hide()
            context.startActivity(Intent(context, LightningDspVolumeManagerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        var optionsDialog: androidx.appcompat.app.AlertDialog? = null
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        if (!app.controllable) content.addView(label(14f).apply { setText(R.string.ldsp_vm_shared_uid) })
        content.addView(MaterialSwitch(context).apply {
            isEnabled = canChangeAppAudio(app)
            setText(R.string.ldsp_vm_mute)
            isChecked = app.muted
            setOnCheckedChangeListener { _, checked -> LightningDspVolumeManager.setAppMuted(app.packageName, checked) }
        })
        content.addView(MaterialSwitch(context).apply {
            setText(R.string.ldsp_vm_hide)
            isChecked = preferences.getBoolean("hidden_${app.packageName}", false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("hidden_${app.packageName}", checked).apply()
                updateApps()
            }
        })
        content.addView(MaterialSwitch(context).apply {
            setText(R.string.ldsp_vm_ignore_buttons)
            isChecked = app.volumeButtonsIgnored
            setOnCheckedChangeListener { _, checked ->
                LightningDspVolumeManager.setAppVolumeButtonsIgnored(app.packageName, checked)
            }
        })
        content.addView(label(14f).apply { setText(R.string.ldsp_vm_pan) })
        content.addView(slider(200).apply {
            isEnabled = canChangeAppAudio(app)
            value = ((app.pan + 1f) * 100f).coerceIn(0f, 200f)
            contentDescription = context.getString(R.string.ldsp_vm_pan)
            addOnChangeListener { _, value, user -> if (user)
                LightningDspVolumeManager.setAppPan(app.packageName, value / 100f - 1f)
            }
        })
        content.addView(MaterialButton(context).apply {
            isEnabled = canChangeAppAudio(app)
            setText(R.string.ldsp_vm_reset)
            setOnClickListener {
                LightningDspVolumeManager.setAppVolume(app.packageName, 1f)
                LightningDspVolumeManager.setAppMuted(app.packageName, false)
                LightningDspVolumeManager.setAppPan(app.packageName, 0f)
                optionsDialog?.dismiss()
            }
        })
        optionsDialog = MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.ldsp_vm_app_options, app.label))
            .setView(content).setPositiveButton(android.R.string.ok, null).show()
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppHolder>() {
        var items = emptyList<AppVolume>()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder = AppHolder()
        override fun onBindViewHolder(holder: AppHolder, position: Int) = holder.bind(items[position])
    }

    private inner class AppHolder : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val row = itemView as LinearLayout
        private val title = label(16f)
        private val valueLabel = label(14f)
        private val volume = slider(100)
        private var bound: AppVolume? = null
        init {
            row.orientation = VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(title, LayoutParams(0, -2, 1f))
            heading.addView(valueLabel)
            heading.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "⋮"
                textSize = 22f
                minimumWidth = dp(48)
                setOnClickListener { bound?.let { showOptions(it) } }
            }, LayoutParams(dp(48), dp(48)))
            row.addView(heading)
            row.addView(volume)
            volume.addOnChangeListener { _, value, user -> if (user) {
                valueLabel.text = appVolumeLabel(value / 100f)
                bound?.let { LightningDspVolumeManager.setAppVolume(it.packageName, value / 100f) }
            } }
            trackDragging(volume)
        }
        fun bind(app: AppVolume) {
            bound = app
            title.text = app.label
            title.alpha = if (app.muted) 0.5f else 1f
            valueLabel.text = appVolumeLabel(app.volume)
            volume.value = (app.volume * 100f).coerceIn(0f, 100f)
            volume.contentDescription = app.label
            volume.isEnabled = canChangeAppAudio(app)
            row.contentDescription = app.label + if (app.active) " · " + context.getString(R.string.ldsp_vm_group_active) else ""
        }
    }

    private fun appVolumeLabel(volume: Float): String {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ceiling = if (maximum > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum else 1f
        return context.getString(R.string.ldsp_vm_capped_volume,
            (volume.coerceIn(0f, 1f) * ceiling * 100).roundToInt(), (ceiling * 100).roundToInt())
    }

    private fun trackDragging(slider: Slider, systemStream: Boolean = false) {
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                if (systemStream) interaction.startSystemDrag() else interaction.startAppDrag()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                if (systemStream) {
                    if (interaction.finishSystemDrag()) refreshStreams()
                } else interaction.finishAppDrag()
                snapshot?.let(::render)
            }
        })
    }
    private fun slider(max: Int) = Slider(context).apply { valueFrom = 0f; valueTo = max.toFloat(); stepSize = 1f }
    private fun label(size: Float) = TextView(context).apply { textSize = size; setPadding(0, dp(4), 0, dp(4)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
