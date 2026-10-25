package lightning.dsp.volumemanager.ui

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import lightning.dsp.R
import lightning.dsp.activity.BaseActivity
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import lightning.dsp.volumemanager.backend.VolumeAccessMode

/** Root-only, native LightningDSP entry point for the integrated per-app mixer. */
class LightningDspVolumeManagerActivity : BaseActivity() {
    private var volumeState by mutableStateOf(lightning.dsp.volumemanager.backend.VolumeManagerState())
    private var enableAfterOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightningDspVolumeManager.initialize(applicationContext)
        LightningDspVolumeManager.resume()
        LightningDspVolumeManager.state.observe(this) { volumeState = it }
        setContent {
            LightningVolumeManagerScreen(
                state = volumeState,
                compact = false,
                onSettings = ::showSettings,
                onShowOverlay = { LightningVolumeOverlay.show(this) },
                onClose = { finish() },
                onManagerEnabledChange = ::setManagerEnabled
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (enableAfterOverlayPermission) {
            enableAfterOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                setManagerEnabled(true)
            } else {
                Toast.makeText(this, R.string.ldsp_vm_overlay_permission, Toast.LENGTH_LONG).show()
            }
        }
        LightningDspVolumeManager.refresh()
    }

    private fun setManagerEnabled(enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(this)) {
            enableAfterOverlayPermission = true
            Toast.makeText(this, R.string.ldsp_vm_overlay_permission, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        LightningDspVolumeManager.setEnabled(enabled)
        if (enabled) LightningVolumeOverlay.show(this) else LightningVolumeOverlay.hide()
    }

    private fun showSettings() {
        val preferences = getSharedPreferences(LightningVolumePanel.PREFERENCES, MODE_PRIVATE)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        controls.addView(MaterialButton(this).apply {
            text = getString(R.string.ldsp_vm_access_mode, LightningDspVolumeManager.accessMode.name)
            setOnClickListener {
                val modes = VolumeAccessMode.entries.toTypedArray()
                MaterialAlertDialogBuilder(this@LightningDspVolumeManagerActivity)
                    .setTitle(R.string.ldsp_vm_access_backend)
                    .setSingleChoiceItems(modes.map { it.name }.toTypedArray(),
                        modes.indexOf(LightningDspVolumeManager.accessMode)) { dialog, index ->
                        LightningDspVolumeManager.setAccessMode(modes[index])
                        text = getString(R.string.ldsp_vm_access_mode, modes[index].name)
                        dialog.dismiss()
                    }.show()
            }
        })
        controls.addView(MaterialButton(this).apply {
            setText(R.string.ldsp_vm_shizuku_permission)
            setOnClickListener { LightningDspVolumeManager.requestShizukuPermission() }
        })
        controls.addView(MaterialSwitch(this).apply {
            setText(R.string.ldsp_vm_per_app)
            isChecked = LightningDspVolumeManager.perAppEnabled
            setOnCheckedChangeListener { _, checked -> LightningDspVolumeManager.setPerAppEnabled(checked) }
        })
        controls.addView(MaterialSwitch(this).apply {
            setText(R.string.ldsp_vm_startup)
            isChecked = LightningDspVolumeManager.startupOnBoot
            setOnCheckedChangeListener { _, checked -> LightningDspVolumeManager.setStartupOnBoot(checked) }
        })
        controls.addView(MaterialSwitch(this).apply {
            setText(R.string.ldsp_vm_show_dsp)
            isChecked = preferences.getBoolean("show_dsp_card", true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("show_dsp_card", checked).apply()
            }
        })
        val floatingThemeButton = MaterialButton(this)
        fun updateFloatingThemeLabel(theme: LightningVolumePanelTheme) {
            floatingThemeButton.text = getString(R.string.ldsp_vm_floating_theme, getString(theme.labelRes))
        }
        updateFloatingThemeLabel(LightningVolumePanelTheme.fromPreference(
            preferences.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
        ))
        floatingThemeButton.setOnClickListener {
            val themes = LightningVolumePanelTheme.entries.toTypedArray()
            val selected = LightningVolumePanelTheme.fromPreference(
                preferences.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
            )
            MaterialAlertDialogBuilder(this@LightningDspVolumeManagerActivity)
                .setTitle(R.string.ldsp_vm_floating_theme_title)
                .setSingleChoiceItems(themes.map { getString(it.labelRes) }.toTypedArray(), themes.indexOf(selected)) { dialog, index ->
                    val theme = themes[index]
                    preferences.edit().putString(LightningVolumePanelTheme.PREFERENCE_KEY, theme.preferenceValue).apply()
                    updateFloatingThemeLabel(theme)
                    dialog.dismiss()
                }.show()
        }
        controls.addView(floatingThemeButton)
        controls.addView(MaterialSwitch(this).apply {
            setText(R.string.ldsp_vm_hide_inactive_dsp)
            isChecked = preferences.getBoolean("hide_inactive_dsp_card", false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("hide_inactive_dsp_card", checked).apply()
            }
        })
        controls.addView(MaterialButton(this).apply {
            setText(R.string.ldsp_vm_grant_overlay)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        controls.addView(MaterialButton(this).apply {
            setText(R.string.ldsp_vm_open_panel)
            setOnClickListener {
                if (!LightningDspVolumeManager.isEnabled) {
                    Toast.makeText(context, R.string.ldsp_vm_off, Toast.LENGTH_SHORT).show()
                } else if (Settings.canDrawOverlays(this@LightningDspVolumeManagerActivity)) {
                    LightningVolumeOverlay.show(this@LightningDspVolumeManagerActivity)
                } else {
                    Toast.makeText(context, R.string.ldsp_vm_overlay_permission, Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
            }
        })
        controls.addView(MaterialButton(this).apply {
            setText(if (LightningVolumeAccessibilityService.isEnabled(context))
                R.string.ldsp_vm_accessibility_disable else R.string.ldsp_vm_accessibility_enable)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@LightningDspVolumeManagerActivity)
                    .setMessage(R.string.ldsp_vm_accessibility_explanation)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        })
        val audio = getSystemService(AudioManager::class.java)
        listOf(
            R.string.ldsp_vm_media to AudioManager.STREAM_MUSIC,
            R.string.ldsp_vm_ring to AudioManager.STREAM_RING,
            R.string.ldsp_vm_call to AudioManager.STREAM_VOICE_CALL,
            R.string.ldsp_vm_alarm to AudioManager.STREAM_ALARM,
            R.string.ldsp_vm_notification to AudioManager.STREAM_NOTIFICATION
        ).forEach { (label, stream) ->
            controls.addView(MaterialSwitch(this).apply {
                text = getString(R.string.ldsp_vm_show_stream, getString(label))
                isChecked = preferences.getBoolean("stream_$stream", stream == AudioManager.STREAM_MUSIC)
                setOnCheckedChangeListener { _, checked ->
                    preferences.edit().putBoolean("stream_$stream", checked).apply()
                }
            })
        }
        controls.addView(MaterialButton(this).apply {
            setText(R.string.ldsp_vm_ringer)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@LightningDspVolumeManagerActivity)
                    .setTitle(R.string.ldsp_vm_ringer)
                    .setSingleChoiceItems(arrayOf(getString(R.string.ldsp_vm_sound),
                        getString(R.string.ldsp_vm_vibrate), getString(R.string.ldsp_vm_silent)),
                        when (audio.ringerMode) { AudioManager.RINGER_MODE_NORMAL -> 0; AudioManager.RINGER_MODE_VIBRATE -> 1; else -> 2 }) { dialog, index ->
                        try {
                            audio.ringerMode = intArrayOf(AudioManager.RINGER_MODE_NORMAL,
                                AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)[index]
                        } catch (_: SecurityException) { requestNotificationPolicy() }
                        dialog.dismiss()
                    }.show()
            }
        })
        controls.addView(MaterialButton(this).apply {
            setText(R.string.ldsp_vm_dnd)
            setOnClickListener {
                val notifications = getSystemService(NotificationManager::class.java)
                if (!notifications.isNotificationPolicyAccessGranted) {
                    requestNotificationPolicy()
                } else {
                    try {
                        notifications.setInterruptionFilter(if (notifications.currentInterruptionFilter ==
                            NotificationManager.INTERRUPTION_FILTER_ALL) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            else NotificationManager.INTERRUPTION_FILTER_ALL)
                    } catch (_: SecurityException) { requestNotificationPolicy() }
                }
            }
        })
        MaterialAlertDialogBuilder(this).setTitle(R.string.ldsp_vm_settings)
            .setView(ScrollView(this).apply { addView(controls) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun requestNotificationPolicy() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
}
