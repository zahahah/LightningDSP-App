package lightning.dsp.session.root

/** Retains only real app sessions, and only in Session Mode while focused or actually playing media. */
internal object KeepEffectsReadyPolicy {
    fun shouldRetain(
        id: Int,
        packageName: String,
        enabled: Boolean,
        globalMode: Boolean,
        foregroundPackage: String?,
        mediaPlayingPackages: Set<String>,
    ): Boolean = id > 0 && packageName.isNotBlank() && enabled && !globalMode &&
        (foregroundPackage == packageName || packageName in mediaPlayingPackages)
}
