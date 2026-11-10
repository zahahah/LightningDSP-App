package lightning.dsp.session.root

/** Retains only real app sessions, and only in Session Mode while the app is foreground/media-backed. */
internal object KeepEffectsReadyPolicy {
    fun shouldRetain(
        id: Int,
        packageName: String,
        enabled: Boolean,
        globalMode: Boolean,
        foregroundPackage: String?,
        mediaAvailablePackages: Set<String>,
    ): Boolean = id > 0 && packageName.isNotBlank() && enabled && !globalMode &&
        (foregroundPackage == packageName || packageName in mediaAvailablePackages)
}
