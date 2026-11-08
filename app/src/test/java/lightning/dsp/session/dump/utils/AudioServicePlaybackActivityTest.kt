package lightning.dsp.session.dump.utils

import org.junit.Assert.*
import org.junit.Test

class AudioServicePlaybackActivityTest {
    private fun row(state: String, sid: String = " sessionId:421") =
        "  AudioPlaybackConfiguration ID:12 type:android.media.AudioTrack u/pid:10322/1567 state:$state attr: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC$sid"

    @Test fun readsStateBeforeAttributesWithoutLosingSessionIdentity() {
        val player = AudioServicePlaybackActivity.parse(row("started")).single()
        assertEquals(10322, player.uid)
        assertEquals(1567, player.pid)
        assertEquals(421, player.sessionId)
        assertEquals(true, player.active)
    }

    @Test fun onlyExplicitStartedStateIsActive() {
        for (state in listOf("paused", "stopped", "released", "idle")) {
            assertEquals(state, false, AudioServicePlaybackActivity.parse(row(state)).single().active)
        }
        for (state in listOf("unknown", "buffering", "2", "started-extra")) {
            assertNull(AudioServicePlaybackActivity.parse(row(state)).single().active)
        }
        assertNull(AudioServicePlaybackActivity.parse(row("started").replace("state:started", "")).single().active)
        assertNull(AudioServicePlaybackActivity.parse(row("started") + " state:paused").single().active)
    }

    @Test fun ignoresHistoricalAndUnrecognizedPlayerLines() {
        val current = row("paused")
        val history = "11-07 21:00:00.000 player event: " + row("started").trim()
        val results = AudioServicePlaybackActivity.parse("$current\n$history\nEvent log:\n  player started piid:12")
        assertEquals(1, results.size)
        assertEquals(false, results.single().active)
    }

    @Test fun preservesLegacyDiscoveryWithoutInventingSessionId() {
        val legacy = row("started", "").replace("AudioPlaybackConfiguration ", "")
        val player = AudioServicePlaybackActivity.parse(legacy).single()
        assertNull(player.sessionId)
        assertEquals(10322, player.uid)
    }

    @Test fun sharedSessionRemainsActiveWhenAnyCurrentPlayerIsStarted() {
        val players = AudioServicePlaybackActivity.parse(row("paused") + "\n" + row("started"))
        assertEquals(2, players.size)
        assertEquals(true, AudioServicePlaybackActivity.merge(players[0].active, players[1].active))
        assertEquals(true, AudioServicePlaybackActivity.merge(true, null))
        assertEquals(false, AudioServicePlaybackActivity.merge(false, false))
        assertNull(AudioServicePlaybackActivity.merge(false, null))
        assertNull(AudioServicePlaybackActivity.merge(null, false))
    }

    @Test fun recognizesCompleteEmptyCurrentPlayersSection() {
        val empty = "PlaybackActivityMonitor dump time: 10:12:34 PM\n playback listeners:\n\n players:\n\n ducked players piids:\n"
        assertTrue(AudioServicePlaybackActivity.hasCompleteEmptyPlayers(empty))
        assertTrue(AudioServicePlaybackActivity.hasCompleteEmptyPlayers(empty.replace("ducked players", "ducked player")))
        // Historical events after the current-player section do not change its emptiness.
        assertTrue(AudioServicePlaybackActivity.hasCompleteEmptyPlayers(empty + "Event log:\n11-07 10:00:00 player started"))
    }

    @Test fun emptyParserResultDoesNotProveEmptyPlayback() {
        for (dump in listOf(
            "", "garbage", "players:\n\n ducked players piids:\n",
            "PlaybackActivityMonitor dump time: 10:12:34 PM\n players:\n",
            "PlaybackActivityMonitor dump time: 10:12:34 PM\n players:\n unsupported player layout\n ducked players piids:\n",
            "PlaybackActivityMonitor dump time: 10:12:34 PM\n players:\n AudioPlaybackConfiguration truncated\n ducked players piids:\n",
            "PlaybackActivityMonitor dump time: 10:12:34 PM\n players:\n${row("started")}\n ducked players piids:\n",
        )) assertFalse(dump, AudioServicePlaybackActivity.hasCompleteEmptyPlayers(dump))
    }
}
