package lightning.dsp.volumemanager.backend

/** Some playback accessors are package-private, even when running under UID 0. */
internal object PlaybackPlatformAccess {
    fun invokeNoArg(receiver: Any, name: String): Any? = receiver.javaClass
        .getDeclaredMethod(name).apply { isAccessible = true }.invoke(receiver)
}
