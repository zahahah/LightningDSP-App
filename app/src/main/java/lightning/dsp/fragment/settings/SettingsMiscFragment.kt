package lightning.dsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Patterns
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import lightning.dsp.BuildConfig
import lightning.dsp.R
import lightning.dsp.activity.OnboardingActivity
import lightning.dsp.api.AutoEqClient
import lightning.dsp.preference.IconPreference
import lightning.dsp.preference.MaterialSwitchPreference
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.AssetManagerExtensions.installPrivateAssets
import lightning.dsp.utils.extensions.ContextExtensions.showAlert
import lightning.dsp.utils.extensions.ContextExtensions.showYesNoAlert
import lightning.dsp.utils.extensions.ContextExtensions.toast
import lightning.dsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import lightning.dsp.utils.isRootless
import lightning.dsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import java.util.Locale

class SettingsMiscFragment : SettingsBaseFragment() {

    private val autoStartNotify by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_autostart_prompt_at_boot)) }
    private val repairAssets by lazy { findPreference<Preference>(getString(R.string.key_troubleshooting_repair_assets)) }
    private val aeqApiUrl by lazy { findPreference<EditTextPreference>(getString(R.string.key_network_autoeq_api_url)) }
    private val debugDatabase by lazy { findPreference<Preference>(getString(R.string.key_debug_database)) }
    private val permSkipPrompt by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_skip_prompt)) }
    private val permAutoStart by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_auto_start)) }
    private val permRestartSetup by lazy { findPreference<Preference>(getString(R.string.key_misc_permission_restart_setup)) }

    private val preferences: Preferences.App by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_misc_preferences, rootKey)

        repairAssets?.setOnPreferenceClickListener {
            requireContext().assets.installPrivateAssets(requireContext(), force = true)
            requireContext().showAlert(R.string.success, R.string.troubleshooting_repair_assets_success)
            true
        }

        aeqApiUrl?.setOnPreferenceChangeListener { _, newValue ->
            if (!Patterns.WEB_URL.matcher(newValue.toString().lowercase(Locale.ROOT)).matches()) {
                requireContext().toast(R.string.network_invalid_url)
                return@setOnPreferenceChangeListener false
            }

            // Verify URL by performing a connection test
            try {
                val client = AutoEqClient(requireContext(), 5, newValue.toString())
                requireContext().toast(R.string.network_autoeq_conntest_running)

                client.queryProfiles(
                    "conntest",
                    onResponse = { _, _ ->
                        context?.toast(R.string.network_autoeq_conntest_done, false)
                    },
                    onFailure = { error ->
                        context?.showYesNoAlert(
                            getString(R.string.network_autoeq_conntest_fail),
                            getString(R.string.network_autoeq_conntest_fail_summary, error)
                        ) {
                            if (it) {
                                // Restore default URL if requested
                                preferences.reset<String>(R.string.key_network_autoeq_api_url)
                                aeqApiUrl?.text = preferences.getDefault(R.string.key_network_autoeq_api_url)
                            }
                        }
                    }
                )
            }
            catch(ex: IllegalArgumentException) {
                // Handle invalid base url argument in retrofit
                requireContext().toast(R.string.network_invalid_url)
                return@setOnPreferenceChangeListener false
            }

            true
        }

        permRestartSetup?.setOnPreferenceClickListener {
            startActivity(Intent(context, OnboardingActivity::class.java).apply {
                putExtra(OnboardingActivity.EXTRA_ROOTLESS_REDO_ADB_SETUP, true)
            })
            true
        }

        updatePermissionStates()

        debugDatabase?.parent?.isVisible = BuildConfig.DEBUG
        autoStartNotify?.isVisible = isRootless()
        permRestartSetup?.parent?.isVisible = isRootless()
    }

    override fun onResume() {
        updatePermissionStates()
        super.onResume()
    }

    private fun updatePermissionStates() {
        if(!isRootless())
            return

        val allowSkipPrompt = context?.hasProjectMediaAppOp() == true
        val allowAutoStart = allowSkipPrompt && Settings.canDrawOverlays(context)

        autoStartNotify?.title = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot
            else R.string.autostart_prompt_at_boot
        )
        autoStartNotify?.summaryOn = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_on
            else R.string.autostart_prompt_at_boot_on
        )
        autoStartNotify?.summaryOff = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_off
            else R.string.autostart_prompt_at_boot_off
        )

        fun getIcon(allowed: Boolean) = context?.let {
            ContextCompat.getDrawable(it,
                if(allowed) R.drawable.ic_twotone_check_circle_24dp
                else R.drawable.ic_twotone_warning_24dp
            )
        }

        fun getSummary(allowed: Boolean) = context?.getString(
            if(allowed) R.string.permission_allowed
            else R.string.permission_not_allowed
        )

        permSkipPrompt?.summary = getSummary(allowSkipPrompt)
        permAutoStart?.summary = getSummary(allowAutoStart)
        permSkipPrompt?.icon = getIcon(allowSkipPrompt)
        permAutoStart?.icon = getIcon(allowAutoStart)
        permRestartSetup?.isVisible = !allowSkipPrompt || !allowAutoStart
    }

    companion object {
        fun newInstance(): SettingsMiscFragment {
            return SettingsMiscFragment()
        }
    }
}
