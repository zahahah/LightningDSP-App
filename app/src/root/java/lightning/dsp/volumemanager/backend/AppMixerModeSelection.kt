package lightning.dsp.volumemanager.backend

/** Selection-only prototype. This preference does not change audio routing or DSP state. */
internal object AppMixerModeSelection {
    const val PREFERENCES_NAME = "app_mixer_mode_poc"
    const val EXCLUDED_PACKAGES_KEY = "excluded_packages"

    fun isIncluded(packageName: String, excludedPackages: Set<String>): Boolean =
        packageName !in excludedPackages

    fun withIncluded(
        packageName: String,
        included: Boolean,
        excludedPackages: Set<String>
    ): Set<String> = if (included) excludedPackages - packageName else excludedPackages + packageName
}
