package lightning.dsp.volumemanager.backend

import android.media.session.PlaybackState

/** Only expose transport actions the active media session explicitly supports. */
internal object MediaPlaybackControlPolicy {
    data class Controls(val visible: Boolean, val playing: Boolean, val canPlay: Boolean, val canPause: Boolean)

    fun controls(state: Int?, actions: Long?): Controls {
        val availableActions = actions ?: 0L
        val canPlay = availableActions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val canPause = availableActions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        return Controls(visible = if (playing) canPause else canPlay, playing = playing,
            canPlay = canPlay, canPause = canPause)
    }
}

/** A short-lived, one-shot marker proving that a paused session followed our Pause button. */
internal class ExplicitMediaPauseRequests<K>(private val confirmationWindowMillis: Long = 5_000L) {
    private val requestedAt = mutableMapOf<K, Long>()

    init { require(confirmationWindowMillis > 0) }

    fun request(session: K, nowElapsedMillis: Long) {
        requestedAt[session] = nowElapsedMillis
    }

    fun consumeIfRecent(session: K, nowElapsedMillis: Long): Boolean {
        val requested = requestedAt.remove(session) ?: return false
        return nowElapsedMillis >= requested && nowElapsedMillis - requested <= confirmationWindowMillis
    }

    fun remove(session: K) { requestedAt.remove(session) }

    fun prune(nowElapsedMillis: Long) {
        requestedAt.entries.removeAll { (_, requested) ->
            nowElapsedMillis < requested || nowElapsedMillis - requested > confirmationWindowMillis
        }
    }

    fun clear() = requestedAt.clear()
}

/** Monotonic retention for paused players; wall-clock changes cannot extend or shorten it. */
internal class PausedMediaRetention(private val durationMillis: Long = 3 * 60 * 1000L) {
    private val deadlines = mutableMapOf<String, Long>()

    fun retainAfterManagerPause(packageName: String, nowElapsedMillis: Long) {
        deadlines[packageName] = nowElapsedMillis + durationMillis
    }

    fun contains(packageName: String, nowElapsedMillis: Long): Boolean {
        val deadline = deadlines[packageName] ?: return false
        if (nowElapsedMillis >= deadline) {
            deadlines.remove(packageName)
            return false
        }
        return true
    }

    fun retained(nowElapsedMillis: Long): Set<String> {
        deadlines.keys.toList().forEach { contains(it, nowElapsedMillis) }
        return deadlines.keys.toSet()
    }

    fun remove(packageName: String) { deadlines.remove(packageName) }
    fun clear() = deadlines.clear()
}
