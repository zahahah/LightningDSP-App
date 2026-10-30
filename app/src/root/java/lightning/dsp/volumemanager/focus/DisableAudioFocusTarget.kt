package lightning.dsp.volumemanager.focus

/** AOSP calls the framework package "android"; Vector's legacy bridge calls it "system". */
internal object DisableAudioFocusTarget {
    @JvmStatic
    fun isFrameworkPackage(packageName: String): Boolean =
        packageName == "android" || packageName == "system"
}
