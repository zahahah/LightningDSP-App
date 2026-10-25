package lightning.dsp.volumemanager.backend

/** Player multipliers are independent of Android's media-stream volume. */
internal object PlaybackVolumePolicy {
    fun volume(value: Float): Float {
        require(value.isFinite()) { "Volume must be finite" }
        return value.coerceIn(0f, 1f)
    }

    fun pan(value: Float): Float {
        require(value.isFinite()) { "Pan must be finite" }
        return value.coerceIn(-1f, 1f)
    }

    fun multiplier(enabled: Boolean, muted: Boolean, requested: Float): Float =
        if (!enabled) 1f else if (muted) 0f else volume(requested)

    /** Cleanup remains best effort per operation, including an unsupported pan method. */
    fun restoreUnity(setVolume: (Float) -> Unit, setPan: (Float) -> Unit) {
        runCatching { setVolume(1f) }
        runCatching { setPan(0f) }
    }

    fun requiresHiddenApiExemption(sdk: Int): Boolean = sdk >= 28

    fun matchesPackage(expected: String, currentPackages: List<String>): Boolean =
        currentPackages.size == 1 && currentPackages.single() == expected

    fun canControl(packageCountForUid: Int): Boolean = packageCountForUid == 1
}
