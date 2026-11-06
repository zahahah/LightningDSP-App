package lightning.dsp.session.dump.utils

import org.junit.Assert.*
import org.junit.Test

class AudioPolicyPlaybackActivityTest {
    private fun client(sid: Int, uid: Int, state: String, port: Int = sid) =
        "      1. Port ID: $port; Session ID: $sid; uid $uid; State: $state\n" +
            "         Attributes: { Content type: AUDIO_CONTENT_TYPE_MUSIC Usage: AUDIO_USAGE_MEDIA }\n"
    private fun dump(rows: String, clients: Int = 1, active: Int = 0, suffix: String = "") =
        " Outputs (1):\n" +
            "  1. Port ID: 1; I/O handle: 21; IOProfile name:deep-buffer-playback; Latency: 90\n" +
            "     Global active count: $active\n" +
            "   - AudioTrack clients ($clients):\n$rows$suffix\n Inputs (0):\n"
    private fun state(text: String, sid: Int = 4961, uid: Int = 10322) =
        AudioPolicyPlaybackActivity.parse(text)[AudioPolicyPlaybackActivity.Identity(sid, uid)]

    @Test fun observedPixelLayoutSeparatesPausedAndActiveSessions() {
        val text = dump(client(2321, 10548, "Inactive") + client(4961, 10322, "Active"), 2, 1)
        assertEquals(false, state(text, 2321, 10548))
        assertEquals(true, state(text))
    }

    @Test fun multipleOutputLayoutKeepsPausedMediaSeparateFromActiveOutput() {
        val text = " Outputs (2):\n" +
            "  1. Port ID: 1; I/O handle: 13; IOProfile name:primary-playback; Latency: 90\n" +
            "     Global active count: 0\n" +
            "   - AudioTrack clients (1):\n" + client(2321, 10548, "Inactive") +
            "  2. Port ID: 6; I/O handle: 21; IOProfile name:deep-buffer-playback; Latency: 90\n" +
            "     Global active count: 1\n" +
            "   - AudioTrack clients (2):\n" + client(2321, 10548, "Inactive", 450) +
            client(4961, 10322, "Active", 677) +
            "   - AudioTrack active (stream) clients (1):\n" +
            client(4961, 10322, "Active", 677).replace("1. Port", "id 1: Port") +
            " Inputs (0):\n"

        val result = AudioPolicyPlaybackActivity.parse(text)
        assertEquals(false, result[AudioPolicyPlaybackActivity.Identity(2321, 10548)])
        assertEquals(true, result[AudioPolicyPlaybackActivity.Identity(4961, 10322)])
    }

    @Test fun anyActiveClientWinsForASharedSession() {
        assertEquals(true, state(dump(client(4961, 10322, "Inactive", 1) + client(4961, 10322, "Active", 2), 2, 1)))
        val activeList = "   - AudioTrack active (stream) clients (1):\n" +
            client(4961, 10322, "Active").replace("1. Port", "id 1: Port")
        assertEquals(true, state(dump(client(4961, 10322, "Inactive"), suffix = activeList)))
    }

    @Test fun unknownStateAndConflictingUidNeverProveInactivity() {
        assertNull(state(dump(client(4961, 10322, "Paused"))))
        assertNull(state(dump(client(4961, 10322, "Inactive") + client(4961, 10323, "Inactive"), 2)))
    }

    @Test fun truncatedMalformedAndUncountedClientsAreUnknown() {
        val text = dump(client(4961, 10322, "Inactive"))
        for (bad in listOf(text.substringBefore(" Inputs"), text.replace("clients (1)", "clients (2)"),
            text.replace("Outputs (1)", "Outputs (2)"), text.replace("Session ID: 4961", "Session ID: invalid"),
            text.replace("State: Inactive", ""), text.replace("Global active count: 0", "Global active count: 1"))) {
            assertTrue(bad, AudioPolicyPlaybackActivity.parse(bad).isEmpty())
        }
    }

    @Test fun incompleteActiveListsAndInputSessionsDoNotBecomePausedPlayback() {
        val text = dump(client(4961, 10322, "Inactive"), suffix =
            "   - AudioTrack active (stream) clients (1):\n" + client(1234, 10322, "Active"))
        assertTrue(AudioPolicyPlaybackActivity.parse(text).isEmpty())
        assertNull(state(" Inputs (1):\n" + client(4961, 10322, "Inactive")))
    }

    @Test fun duplicatePortsAndMismatchedActiveClientsCannotHideAPlayingClient() {
        val duplicate = dump(client(4961, 10322, "Inactive", 1) + client(4961, 10322, "Inactive", 1), 2)
        assertTrue(AudioPolicyPlaybackActivity.parse(duplicate).isEmpty())
        val one = dump(client(4961, 10322, "Inactive"))
        val duplicateOutput = one.replace("Outputs (1)", "Outputs (2)").replace(" Inputs (0):",
            "  2. Port ID: 2; I/O handle: 21; IOProfile name:duplicate; Latency: 90\n     Global active count: 0\n Inputs (0):")
        assertTrue(AudioPolicyPlaybackActivity.parse(duplicateOutput).isEmpty())
        val wrongPort = "   - AudioTrack active (stream) clients (1):\n" + client(4961, 10322, "Active", 99)
        assertTrue(AudioPolicyPlaybackActivity.parse(dump(client(4961, 10322, "Inactive", 1), suffix = wrongPort)).isEmpty())
    }

    @Test fun unrelatedOutputLayoutsFailSafeAndEmptyOutputsHaveNoActivity() {
        assertTrue(AudioPolicyPlaybackActivity.parse(" Outputs (0):\n Inputs (0):\n").isEmpty())
        assertTrue(AudioPolicyPlaybackActivity.parse(dump(client(4961, 10322, "Inactive"))
            .replace("I/O handle", "Unknown handle")).isEmpty())
    }
}
