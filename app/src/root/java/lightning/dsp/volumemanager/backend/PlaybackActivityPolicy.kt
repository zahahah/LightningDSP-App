package lightning.dsp.volumemanager.backend

/** Playback lifecycle, not audibility: Android isActive() excludes client-muted players. */
internal object PlaybackActivityPolicy {
    // AudioPlaybackConfiguration.PLAYER_STATE_STARTED, hidden from the public SDK.
    private const val PLAYER_STATE_STARTED = 2

    fun isPlaying(configuration: Any): Boolean {
        val state = try {
            PlaybackPlatformAccess.invokeNoArg(configuration, "getPlayerState") as Int
        } catch (_: NoSuchMethodException) {
            // Older/vendor implementations without the lifecycle accessor retain discovery.
            return PlaybackPlatformAccess.invokeNoArg(configuration, "isActive") as Boolean
        }
        return state == PLAYER_STATE_STARTED
    }
}
