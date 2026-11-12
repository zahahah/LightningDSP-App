package lightning.dsp.volumemanager.backend

/** Remote session state changes alone must never open the volume panel. */
internal object RemoteVolumeEventPolicy {
    fun shouldObserve(manufacturer: String?): Boolean = manufacturer.equals("samsung", ignoreCase = true)
    fun shouldShow(flags: Int, bypass: Boolean): Boolean = !bypass && flags and 1 != 0
}
