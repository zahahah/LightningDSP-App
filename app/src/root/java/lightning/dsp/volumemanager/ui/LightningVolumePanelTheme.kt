package lightning.dsp.volumemanager.ui

import lightning.dsp.R

/** Color mode for the floating panel, independent of the app's main-screen theme. */
internal enum class LightningVolumePanelTheme(
    val preferenceValue: String,
    val labelRes: Int,
) {
    SYSTEM("system", R.string.ldsp_vm_theme_system),
    LIGHT("light", R.string.ldsp_vm_theme_light),
    DARK("dark", R.string.ldsp_vm_theme_dark);

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        const val PREFERENCE_KEY = "floating_panel_theme"

        fun fromPreference(value: String?): LightningVolumePanelTheme =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}
