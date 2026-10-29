package lightning.dsp.volumemanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import lightning.dsp.R
import lightning.dsp.activity.BaseActivity
import lightning.dsp.activity.MainActivity
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager

/** Root-only, native LightningDSP entry point for the integrated per-app mixer. */
class LightningDspVolumeManagerActivity : BaseActivity() {
    private var volumeState by mutableStateOf(lightning.dsp.volumemanager.backend.VolumeManagerState())
    private var enableAfterOverlayPermission = false
    internal class NavigationState : ViewModel() {
        val policy = VolumeManagerNavigationPolicy()
        private var cancelPermissionRequest: (() -> Unit)? = null

        fun requestShizukuPermission() {
            cancelPermissionRequest?.invoke()
            policy.externalFlow = true
            // Capture only the pure policy, never this Activity or its ViewModel.
            val requestPolicy = policy
            cancelPermissionRequest = LightningDspVolumeManager.requestShizukuPermission {
                requestPolicy.externalFlowFinished()
            }
        }

        override fun onCleared() {
            cancelPermissionRequest?.invoke()
            super.onCleared()
        }
    }
    private val navigationState by viewModels<NavigationState>()
    private val navigation get() = navigationState.policy

    override fun onStop() {
        navigation.stopped(isChangingConfigurations, isFinishing)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // A newly restored process returns to main. A configuration recreation keeps
        // the live policy in the ViewModel, including its temporary permission flow.
        outState.putBoolean("volume_return_main", !navigation.externalFlow)
        outState.putBoolean("volume_external_flow", navigation.externalFlow)
        outState.putBoolean("volume_enable_after_overlay", enableAfterOverlayPermission)
        super.onSaveInstanceState(outState)
    }

    private fun closeMixer() {
        if (isTaskRoot || navigation.returnToMain) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }

    private fun openPermissionSettings(intent: Intent) {
        navigation.externalFlow = true
        try {
            startActivity(intent)
        } catch (failure: RuntimeException) {
            navigation.externalFlow = false
            throw failure
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation.restore(
            returnToMain = savedInstanceState?.getBoolean("volume_return_main") ?: false,
            externalFlow = savedInstanceState?.getBoolean("volume_external_flow") ?: false
        )
        enableAfterOverlayPermission = savedInstanceState?.getBoolean("volume_enable_after_overlay") ?: false
        LightningDspVolumeManager.initialize(applicationContext)
        LightningDspVolumeManager.state.observe(this) { volumeState = it }
        setContent {
            LightningVolumeManagerScreen(
                state = volumeState,
                compact = false,
                onSettings = ::showSettings,
                onShowOverlay = ::showOverlayOrRequestPermission,
                onClose = ::closeMixer,
                onManagerEnabledChange = ::setManagerEnabled
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (navigation.returnToMain) {
            closeMixer()
            return
        }
        navigation.externalFlow = false
        if (enableAfterOverlayPermission) {
            enableAfterOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                setManagerEnabled(true)
            } else {
                Toast.makeText(this, R.string.ldsp_vm_overlay_permission, Toast.LENGTH_LONG).show()
            }
        }
        // An explicit return from root/overlay settings is a safe retry point.
        LightningDspVolumeManager.resume()
        LightningDspVolumeManager.refresh()
    }

    private fun setManagerEnabled(enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(this)) {
            enableAfterOverlayPermission = true
            Toast.makeText(this, R.string.ldsp_vm_overlay_permission, Toast.LENGTH_LONG).show()
            openPermissionSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        LightningDspVolumeManager.setEnabled(enabled)
        if (enabled) LightningVolumeOverlay.show(this) else LightningVolumeOverlay.hide()
    }

    private fun showOverlayOrRequestPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.ldsp_vm_overlay_permission, Toast.LENGTH_LONG).show()
            openPermissionSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        LightningVolumeOverlay.show(this)
    }

    private fun showSettings() {
        // Scope the mixer accent to this menu, retaining day/night surface colors.
        val settingsContext = ContextThemeWrapper(this, R.style.ThemeOverlay_LightningDSP_VolumeSettings)
        val preferences = getSharedPreferences(LightningVolumePanel.PREFERENCES, MODE_PRIVATE)
        val controls = LinearLayout(settingsContext).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding((24 * density).toInt(), (8 * density).toInt(),
                (24 * density).toInt(), (8 * density).toInt())
        }
        fun addSection(title: Int) {
            controls.addView(TextView(settingsContext).apply {
                setText(title)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
                ViewCompat.setAccessibilityHeading(this, true)
            })
        }
        addSection(R.string.ldsp_vm_settings_behavior)
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_per_app)
            isChecked = LightningDspVolumeManager.perAppEnabled
            setOnCheckedChangeListener { _, checked -> LightningDspVolumeManager.setPerAppEnabled(checked) }
        })
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_startup)
            isChecked = LightningDspVolumeManager.startupOnBoot
            setOnCheckedChangeListener { _, checked -> LightningDspVolumeManager.setStartupOnBoot(checked) }
        })
        addSection(R.string.ldsp_vm_settings_app_list)
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_hide_system_apps)
            isChecked = preferences.getBoolean("hide_system_apps", false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("hide_system_apps", checked).apply()
            }
        })
        addSection(R.string.ldsp_vm_settings_dsp_card)
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_show_dsp)
            isChecked = preferences.getBoolean("show_dsp_card", true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("show_dsp_card", checked).apply()
            }
        })
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_hide_inactive_dsp)
            isChecked = preferences.getBoolean("hide_inactive_dsp_card", false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("hide_inactive_dsp_card", checked).apply()
            }
        })
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_monochrome_dsp_bar)
            isChecked = preferences.getBoolean("dsp_bar_monochrome", false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("dsp_bar_monochrome", checked).apply()
            }
        })
        addSection(R.string.ldsp_vm_settings_floating_controls)
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_group_system_streams)
            isChecked = preferences.getBoolean("group_system_streams", true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("group_system_streams", checked).apply()
            }
        })
        controls.addView(MaterialSwitch(settingsContext).apply {
            setText(R.string.ldsp_vm_media_icon_tap_expand)
            isChecked = preferences.getBoolean(MediaGroupExpansionPolicy.PREFERENCE_KEY, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(MediaGroupExpansionPolicy.PREFERENCE_KEY, checked).apply()
            }
        })
        controls.addView(MaterialButton(settingsContext).apply {
            setText(R.string.ldsp_vm_grant_overlay)
            setOnClickListener {
                openPermissionSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        controls.addView(TextView(settingsContext).apply {
            setText(R.string.ldsp_vm_floating_theme_section)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val floatingThemeButton = MaterialButton(settingsContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        fun updateFloatingThemeLabel(theme: LightningVolumePanelTheme) {
            floatingThemeButton.setText(theme.labelRes)
        }
        updateFloatingThemeLabel(LightningVolumePanelTheme.fromPreference(
            preferences.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
        ))
        floatingThemeButton.setOnClickListener {
            val themes = LightningVolumePanelTheme.entries.toTypedArray()
            val selected = LightningVolumePanelTheme.fromPreference(
                preferences.getString(LightningVolumePanelTheme.PREFERENCE_KEY, null)
            )
            MaterialAlertDialogBuilder(settingsContext)
                .setTitle(R.string.ldsp_vm_floating_theme_title)
                .setSingleChoiceItems(themes.map { getString(it.labelRes) }.toTypedArray(), themes.indexOf(selected)) { dialog, index ->
                    val theme = themes[index]
                    preferences.edit().putString(LightningVolumePanelTheme.PREFERENCE_KEY, theme.preferenceValue).apply()
                    updateFloatingThemeLabel(theme)
                    dialog.dismiss()
                }.show()
        }
        controls.addView(floatingThemeButton)
        val scrollingControls = ScrollView(settingsContext).apply { addView(controls) }
        val dialog = MaterialAlertDialogBuilder(settingsContext)
            .setBackgroundInsetStart(0).setBackgroundInsetEnd(0)
            .setBackgroundInsetTop(0).setBackgroundInsetBottom(0)
            .setTitle(R.string.ldsp_vm_settings)
            .setView(scrollingControls)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            val appContent = findViewById<View>(android.R.id.content)
            val window = dialog.window ?: return@setOnShowListener
            // Use the actual app content area, not the physical screen (split-screen/insets).
            val targetWidth = (appContent.width * 0.94f).toInt().coerceAtLeast(1)
            val maximumHeight = (appContent.height * 0.80f).toInt().coerceAtLeast(1)
            window.setGravity(Gravity.CENTER)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.decorView.doOnLayout { decor ->
                if (decor.height > maximumHeight) {
                    // Keep title/actions outside the scroll area so OK stays reachable.
                    val fixedHeight = (decor.height - scrollingControls.height).coerceAtLeast(0)
                    scrollingControls.layoutParams = scrollingControls.layoutParams.apply {
                        height = (maximumHeight - fixedHeight).coerceAtLeast(1)
                    }
                    window.setLayout(targetWidth, maximumHeight)
                }
            }
        }
        dialog.show()
    }

}
