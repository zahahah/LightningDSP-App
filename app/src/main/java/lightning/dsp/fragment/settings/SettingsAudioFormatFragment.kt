package lightning.dsp.fragment.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lightning.dsp.R
import lightning.dsp.activity.OnboardingActivity
import lightning.dsp.interop.BenchmarkManager
import lightning.dsp.interop.PostGainRangePolicy
import lightning.dsp.preference.MaterialSeekbarPreference
import lightning.dsp.preference.MaterialSwitchPreference
import lightning.dsp.service.RootAudioProcessorService
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.requestIgnoreBatteryOptimizations
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import lightning.dsp.utils.extensions.ContextExtensions.showAlert
import lightning.dsp.utils.extensions.ContextExtensions.toast
import lightning.dsp.utils.extensions.PermissionExtensions.hasDumpPermission
import lightning.dsp.utils.isRoot
import lightning.dsp.utils.isRootless
import lightning.dsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber

class SettingsAudioFormatFragment : SettingsBaseFragment() {

    private val encoding by lazy { findPreference<ListPreference>(getString(R.string.key_audioformat_encoding)) }
    private val bufferSize by lazy { findPreference<MaterialSeekbarPreference>(getString(R.string.key_audioformat_buffersize)) }
    private val legacyMode by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_processing)) }
    private val allMixers by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_all_mixers)) }
    private val enhancedMode by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_enhanced_processing)) }
    private val enhancedModeInfo by lazy { findPreference<Preference>(getString(R.string.key_audioformat_enhanced_processing_info)) }
    private val benchmark by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_optimization_benchmark)) }
    private val benchmarkRefresh by lazy { findPreference<Preference>(getString(R.string.key_audioformat_optimization_refresh)) }
    private val extraPostGain by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_output_extra_postgain)) }

    private val preferences: Preferences.App by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_audio_format_preferences, rootKey)
        updateAllMixersAvailability(preferences.get<Boolean>(R.string.key_audioformat_processing))

        extraPostGain?.apply {
            isVisible = isRoot()
            isChecked = requireContext()
                .getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE)
                .getBoolean(getString(R.string.key_output_extra_postgain), PostGainRangePolicy.DEFAULT_EXTENDED)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val outputPreferences = requireContext()
                    .getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE)
                val key = getString(R.string.key_output_extra_postgain)
                val storedGain = outputPreferences.getFloat(getString(R.string.key_output_postgain), 0f)
                val currentExtended = outputPreferences.getBoolean(key, PostGainRangePolicy.DEFAULT_EXTENDED)
                val boundedGain = PostGainRangePolicy.onToggle(storedGain, currentExtended, enabled)
                outputPreferences.edit()
                    .putBoolean(key, enabled)
                    .putFloat(getString(R.string.key_output_postgain), boundedGain)
                    .apply()
                true
            }
        }

        // Root: Hide audio format & benchmark category
        encoding?.parent?.isVisible = isRootless()
        benchmark?.parent?.isVisible = !isRoot()

        // Rootless: Hide audio processing category
        legacyMode?.parent?.isVisible = isRoot()
        legacyMode?.setOnPreferenceChangeListener { _, newValue ->
            val globalMode = newValue as Boolean
            updateAllMixersAvailability(globalMode)
            if (!globalMode)
                requireContext().requestIgnoreBatteryOptimizations()
            else
                enhancedMode?.isChecked = false

            RootAudioProcessorService.updateLegacyMode(requireContext(), globalMode)
            true
        }
        enhancedMode?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                // Check DUMP permissions
                if(!requireContext().hasDumpPermission()) {
                    Timber.i("Launching enhanced processing onboarding")

                    Intent(requireContext(), OnboardingActivity::class.java).let {
                        it.putExtra(OnboardingActivity.EXTRA_ROOT_SETUP_DUMP_PERM, true)
                        startActivity(it)
                    }
                    return@setOnPreferenceChangeListener false
                }

                RootAudioProcessorService.startServiceEnhanced(requireContext())
            }
            else {
                requireContext().toast(R.string.audio_format_media_apps_need_restart)
                RootAudioProcessorService.stopService(requireContext())
            }
            true
        }
        enhancedModeInfo?.setOnPreferenceClickListener {
            context?.showAlert(
                R.string.audio_format_enhanced_processing_info_title,
                R.string.audio_format_enhanced_processing_info_content
            )
            true
        }

        fun runBenchmark() = context?.let { ctx ->
            BenchmarkManager.runBenchmarks(ctx) {
                CoroutineScope(Dispatchers.Main).launch {
                    benchmark?.isChecked = BenchmarkManager.hasBenchmarksCached()
                }
            }
        }

        benchmark?.isChecked = BenchmarkManager.hasBenchmarksCached()
        benchmark?.setOnPreferenceChangeListener { _, newValue ->
            if(newValue as Boolean)
                runBenchmark()
            else
                BenchmarkManager.clearBenchmarks()
            true
        }
        benchmarkRefresh?.setOnPreferenceClickListener {
            runBenchmark()
            true
        }

        bufferSize?.setDefaultValue(preferences.getDefault<Float>(R.string.key_audioformat_buffersize))
        bufferSize?.setOnPreferenceChangeListener { _, newValue ->
            if((newValue as Float) <= 1024){
                requireContext().toast(R.string.audio_format_buffer_size_warning_low_value, false)
            }
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
        encoding?.setOnPreferenceChangeListener { _, _ ->
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateAllMixersAvailability(preferences.get<Boolean>(R.string.key_audioformat_processing))
    }

    private fun updateAllMixersAvailability(globalMode: Boolean) {
        allMixers?.apply {
            isVisible = isRoot()
            // Global's XML dependency is inverted for media-session scanning.
            // Change availability only; retain the user's saved all-mixers choice.
            isEnabled = isRoot() && globalMode
        }
    }

    companion object {
        fun newInstance(): SettingsAudioFormatFragment {
            return SettingsAudioFormatFragment()
        }
    }
}
