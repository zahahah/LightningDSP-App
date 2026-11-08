package lightning.dsp.fragment.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lightning.dsp.R
import lightning.dsp.activity.OnboardingActivity
import lightning.dsp.interop.BenchmarkManager
import lightning.dsp.interop.AAudioCompatibilityOption
import lightning.dsp.interop.AAudioSessionReuseOption
import lightning.dsp.interop.AAudioCompatibilityResult
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
    private val globalMode by lazy {
        findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_processing_global_option))
    }
    private val sessionMode by lazy {
        findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_processing_session_option))
    }
    private val allMixers by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_all_mixers)) }
    private val enhancedMode by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_enhanced_processing)) }
    private val audioCompat by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_aaudio_compat)) }
    private val aaudioSessionReuse by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_aaudio_reuse_session)) }
    private val enhancedModeInfo by lazy { findPreference<Preference>(getString(R.string.key_audioformat_enhanced_processing_info)) }
    private val benchmark by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_optimization_benchmark)) }
    private val benchmarkRefresh by lazy { findPreference<Preference>(getString(R.string.key_audioformat_optimization_refresh)) }
    private val extraPostGain by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_output_extra_postgain)) }

    private val preferences: Preferences.App by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_audio_format_preferences, rootKey)
        updateProcessingMode(preferences.get<Boolean>(R.string.key_audioformat_processing))

        audioCompat?.apply {
            val compatContext = requireContext().applicationContext
            isVisible = isRoot()
            isChecked = AAudioCompatibilityOption.requested(compatContext)
            if (isRoot()) {
                isEnabled = false
                lifecycleScope.launch {
                    val actual = withContext(Dispatchers.IO) { AAudioCompatibilityOption.actual() }
                    val requested = AAudioCompatibilityOption.requested(compatContext)
                if (actual != null && actual == requested) {
                    isEnabled = true
                    aaudioSessionReuse?.isEnabled = requested
                }
                    else {
                        if (actual != null) isChecked = actual
                        setSummary(R.string.audio_format_aaudio_compat_unknown)
                    }
                }
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val enabled = newValue as Boolean
                val appContext = requireContext().applicationContext
                preference.isEnabled = false
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AAudioCompatibilityOption.set(appContext, enabled)
                    }
                    when (result) {
                        AAudioCompatibilityResult.APPLIED -> {
                            preference.isEnabled = true
                            (preference as MaterialSwitchPreference).isChecked = enabled
                            aaudioSessionReuse?.isEnabled = enabled
                            if (isAdded) requireContext().toast(R.string.audio_format_aaudio_compat_restart)
                        }
                        AAudioCompatibilityResult.FAILED -> {
                            preference.isEnabled = true
                            if (isAdded) requireContext().toast(R.string.audio_format_aaudio_compat_failed)
                        }
                        AAudioCompatibilityResult.UNKNOWN -> {
                            preference.setSummary(R.string.audio_format_aaudio_compat_unknown)
                            if (isAdded) requireContext().toast(R.string.audio_format_aaudio_compat_unknown)
                        }
                    }
                }
                false
            }
        }

        aaudioSessionReuse?.apply {
            val compatContext = requireContext().applicationContext
            isVisible = isRoot()
            isChecked = AAudioSessionReuseOption.requested(compatContext)
            isEnabled = isRoot() && AAudioCompatibilityOption.requested(compatContext)
            if (isRoot()) {
                isEnabled = false
                lifecycleScope.launch {
                    val actual = withContext(Dispatchers.IO) { AAudioSessionReuseOption.actual() }
                    val expected = AAudioSessionReuseOption.requested(compatContext) &&
                        AAudioCompatibilityOption.requested(compatContext)
                    if (actual != null && actual == expected) {
                        isEnabled = AAudioCompatibilityOption.requested(compatContext)
                    } else {
                        if (actual != null && AAudioCompatibilityOption.requested(compatContext)) isChecked = actual
                        setSummary(R.string.audio_format_aaudio_compat_unknown)
                    }
                }
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val enabled = newValue as Boolean
                val appContext = requireContext().applicationContext
                preference.isEnabled = false
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AAudioSessionReuseOption.set(appContext, enabled)
                    }
                    when (result) {
                        AAudioCompatibilityResult.APPLIED -> {
                            preference.isEnabled = AAudioCompatibilityOption.requested(appContext)
                            (preference as MaterialSwitchPreference).isChecked = enabled
                            if (isAdded) requireContext().toast(R.string.audio_format_aaudio_reuse_session_restart)
                        }
                        AAudioCompatibilityResult.FAILED -> {
                            preference.isEnabled = AAudioCompatibilityOption.requested(appContext)
                            if (isAdded) requireContext().toast(R.string.audio_format_aaudio_compat_failed)
                        }
                        AAudioCompatibilityResult.UNKNOWN -> {
                            preference.setSummary(R.string.audio_format_aaudio_compat_unknown)
                            if (isAdded) requireContext().toast(R.string.audio_format_aaudio_compat_unknown)
                        }
                    }
                }
                false
            }
        }

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
        globalMode?.parent?.isVisible = isRoot()
        globalMode?.setOnPreferenceChangeListener { _, newValue ->
            setProcessingMode(newValue as Boolean)
            false
        }
        sessionMode?.setOnPreferenceChangeListener { _, newValue ->
            setProcessingMode(!(newValue as Boolean))
            false
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
        updateProcessingMode(preferences.get<Boolean>(R.string.key_audioformat_processing))
    }

    private fun updateProcessingMode(globalEnabled: Boolean) {
        globalMode?.isChecked = globalEnabled
        sessionMode?.isChecked = !globalEnabled
        enhancedMode?.isVisible = isRoot() && !globalEnabled
        enhancedModeInfo?.isVisible = isRoot() && !globalEnabled
        if (isRoot() && !globalEnabled) enhancedMode?.apply {
            val permission = requireContext().hasDumpPermission()
            // Display unavailability without changing the user's saved preference.
            isPersistent = false
            isChecked = permission && preferences.get<Boolean>(R.string.key_audioformat_enhanced_processing)
            isPersistent = true
            summaryOff = getString(if (permission) R.string.audio_format_enhanced_processing_off
                else R.string.enhanced_processing_missing_perm)
        }
        allMixers?.apply {
            // Coverage supplements outputs in Global Mode and unreported sessions
            // in Session Mode; the stored opt-in remains shared between the modes.
            isVisible = isRoot()
            isEnabled = isRoot()
        }
    }

    private fun setProcessingMode(globalEnabled: Boolean) {
        val previousMode = preferences.get<Boolean>(R.string.key_audioformat_processing)
        if (previousMode != globalEnabled) {
            preferences.set(R.string.key_audioformat_processing, globalEnabled)
            if (!globalEnabled) requireContext().requestIgnoreBatteryOptimizations()
            RootAudioProcessorService.updateGlobalMode(requireContext(), globalEnabled)
        }
        updateProcessingMode(globalEnabled)
    }

    companion object {
        fun newInstance(): SettingsAudioFormatFragment {
            return SettingsAudioFormatFragment()
        }
    }
}
