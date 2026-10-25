package lightning.dsp.volumemanager.backend

/** No normal application UID may run the engine; only the owning app may command it. */
internal object VolumeBridgeIdentity {
    fun privileged(uid: Int): Boolean = uid == 0 || uid == 2000
    fun owns(callerUid: Int, expectedUid: Int, packageName: String, packages: List<String>): Boolean =
        callerUid == expectedUid && packageName in packages
}
