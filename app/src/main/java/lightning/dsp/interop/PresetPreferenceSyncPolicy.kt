package lightning.dsp.interop

import lightning.dsp.utils.Constants

/**
 * Presets replace DSP SharedPreferences files, so every DSP namespace must be
 * applied even if an engine's incremental cache does not observe the change.
 */
internal object PresetPreferenceSyncPolicy {
    fun forceUpdateNamespacesFor(action: String?): Array<String>? =
        if (action == Constants.ACTION_PRESET_LOADED) Constants.DSP_PREFERENCE_NAMESPACES.toTypedArray() else null
}
