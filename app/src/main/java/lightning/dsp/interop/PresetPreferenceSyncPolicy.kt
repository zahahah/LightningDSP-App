package lightning.dsp.interop

import lightning.dsp.utils.Constants

/**
 * Presets replace DSP SharedPreferences files, so every DSP namespace must be
 * applied even if an engine's incremental cache does not observe the change.
 */
internal object PresetPreferenceSyncPolicy {
    private val dspNamespaces = listOf(
        Constants.PREF_OUTPUT,
        Constants.PREF_COMPANDER,
        Constants.PREF_BASS,
        Constants.PREF_EQ,
        Constants.PREF_GEQ,
        Constants.PREF_REVERB,
        Constants.PREF_STEREOWIDE,
        Constants.PREF_CROSSFEED,
        Constants.PREF_TUBE,
        Constants.PREF_DDC,
        Constants.PREF_LIVEPROG,
        Constants.PREF_CONVOLVER,
    )

    fun forceUpdateNamespacesFor(action: String?): Array<String>? =
        if (action == Constants.ACTION_PRESET_LOADED) dspNamespaces.toTypedArray() else null
}
