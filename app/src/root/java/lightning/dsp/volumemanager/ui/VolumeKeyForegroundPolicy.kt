package lightning.dsp.volumemanager.ui

/** Foreground identity uses validated activity metadata only, never accessibility node contents. */
internal class VolumeKeyForegroundPolicy(private val ownPackage: String) {
    var foregroundPackage: String? = null
        private set

    fun windowChanged(packageName: String?, validatedActivity: Boolean) {
        // Transient volume/shade and our own overlay are not the activity underneath them.
        if (packageName == "com.android.systemui" || (packageName == ownPackage && !validatedActivity)) return
        foregroundPackage = packageName?.takeIf { it.isNotBlank() && validatedActivity }
    }

    fun mayIntercept(ignoredPackages: Set<String>): Boolean = foregroundPackage?.let {
        it !in ignoredPackages
    } ?: ignoredPackages.isEmpty()

    fun reset() { foregroundPackage = null }
}
