package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

/** Sanitized Android dump layout: output FAST is not a fast AudioTrack. */
class GlobalMixerCoverageTest {
    private data class Playback(val session: Int = 361, val uid: Int = 10322, val port: Int = 70,
                                val type: String = "", val format: String = "00000001",
                                val channels: String = "00000003", val flags: String = "000",
                                val bitPerfect: Boolean = false)
    private fun effect(id: Int, session: Int, suspended: Boolean = false) = """
        Effect ID $id:
          Session State Registered Internal Enabled Suspended:
          $session 003 y n y ${if (suspended) "y" else "n"}
          - UUID: f27317f4-c984-4de6-9a90-545759495bf2
    """.trimIndent()
    private fun output(id: Int, tracks: List<Playback>, effects: List<String> = emptyList(), type: String = "MIXER") = buildString {
        append("Output thread 0x$id, name AudioOut, tid 100, type 0 ($type):\n")
        append("  I/O handle: $id\n  Standby: ${if (tracks.isEmpty()) "yes" else "no"}\n")
        append("  ${tracks.size} Tracks${if (tracks.isEmpty()) "" else " of which ${tracks.size} are active"}\n")
        append("    Type Id Active Client(pid/uid) Session Port Id S Flags Format Chn mask SRate ST BitPerfect InternalMute Latency\n")
        tracks.forEachIndexed { index, t ->
            append("    ${t.type} ${index + 1} yes 9716/ ${t.uid} ${t.session} ${t.port} A 0x${t.flags} ${t.format} ${t.channels} 44100 3 1 2 -inf 0 0 0 -inf false 0000 12376 12376 A 0 0 ${t.bitPerfect} false 455.17\n")
        }
        append("  ${effects.size} Effect Chains\n")
        effects.forEach { append("  1 effects for session 0\n$it\n") }
    }
    private fun policyOutput(id: Int, tracks: List<Playback>, flags: String = "108", usage: String = "MEDIA") = buildString {
        append("  1. Port ID: $id; I/O handle: $id; IOProfile name: playback; 0x$flags (FLAGS)\n")
        append("    Global active count: ${tracks.size}\n")
        tracks.forEachIndexed { index, t ->
            append("    ${index + 1}. Port ID: ${t.port}; Session ID: ${t.session}; uid ${t.uid}; State: Active\n")
            append("      Format: AUDIO_FORMAT_PCM_16_BIT; 1; Channel mask: 0x3\n")
            append("      Stream: 3; Flags: 0x0; Usage: AUDIO_USAGE_$usage\n")
        }
    }
    private fun inspect(tracks: List<Playback> = listOf(Playback()), globalOutput: Int = 13,
                        suspended: Boolean = false, extra: List<String> = emptyList(), owned: Set<Int> = emptySet(),
                        flags: String = "104", type: String = "MIXER", usage: String = "MEDIA"):
        GlobalMixerCoverage.Snapshot {
        val baseline = output(globalOutput, emptyList(), listOf(effect(27, 0, suspended)))
        val flinger = baseline + output(21, tracks, extra, type)
        val policy = " Outputs (2):\n" + policyOutput(globalOutput, emptyList()) + policyOutput(21, tracks, flags, usage) +
            "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: $globalOutput; Session: 0;\n"
        return GlobalMixerCoverage.inspect(flinger, policy, 27, owned)
    }

    @Test fun normalRawFastOutputIsEligibleEvenAtZeroVolume() {
        val snapshot = inspect()
        assertTrue(snapshot.reason, snapshot.known)
        assertEquals(listOf(361), snapshot.targets.map { it.id })
        assertEquals(13, snapshot.globalOutput)
    }
    @Test fun deepBufferAndGameAreEligible() {
        assertEquals(1, inspect(flags = "8", usage = "GAME").targets.size)
    }
    @Test fun actualFastTrackIsExcluded() { assertTrue(inspect(listOf(Playback(type = "F"))).targets.isEmpty()) }
    @Test fun nonzeroTrackFlagsAreExcluded() { assertTrue(inspect(listOf(Playback(flags = "001"))).targets.isEmpty()) }
    @Test fun bitPerfectTrackIsExcluded() { assertTrue(inspect(listOf(Playback(bitPerfect = true))).targets.isEmpty()) }
    @Test fun specialAndUnknownOutputFlagsAreExcluded() {
        listOf("1", "10", "1000", "4000", "40000", "80000000").forEach {
            assertTrue("flags=$it", inspect(flags = it).targets.isEmpty())
        }
    }
    @Test fun specialOutputTypesAreExcluded() {
        listOf("DIRECT", "OFFLOAD", "MMAP", "UNKNOWN").forEach { assertTrue(inspect(type = it).targets.isEmpty()) }
    }
    @Test fun unsupportedFormatChannelsAndUsageAreExcluded() {
        assertTrue(inspect(listOf(Playback(format = "08000000"))).targets.isEmpty())
        assertTrue(inspect(listOf(Playback(channels = "00000001"))).targets.isEmpty())
        assertTrue(inspect(usage = "VOICE_COMMUNICATION").targets.isEmpty())
    }
    @Test fun sharedSessionCollectsAllCorroboratedPorts() {
        assertEquals(setOf(70, 71), inspect(listOf(Playback(), Playback(port = 71))).targets.single().ports)
    }
    @Test fun reusedSessionAcrossUidsIsNotOwned() {
        assertTrue(inspect(listOf(Playback(), Playback(uid = 10323, port = 71))).targets.isEmpty())
    }
    @Test fun duplicatePortsFailClosed() { assertFalse(inspect(listOf(Playback(), Playback())).known) }
    @Test fun preexistingPositiveEffectIsNotAcquired() {
        assertTrue(inspect(extra = listOf(effect(31, 361))).targets.isEmpty())
    }
    @Test fun ownedEffectCanBeVerifiedWithoutLosingItsTarget() {
        val snapshot = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        assertEquals(1, snapshot.targets.size)
        assertTrue(snapshot.ownsAttachment(snapshot.targets.single(), 31))
        assertFalse(snapshot.ownsAttachment(snapshot.targets.single(), 32))
    }
    @Test fun suspendedBaselineStillReservesItsOutput() {
        val track = listOf(Playback())
        val flinger = output(21, track, listOf(effect(27, 0, true)))
        val policy = " Outputs (1):\n" + policyOutput(21, track) +
            "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 21; Session: 0;\n"
        val snapshot = GlobalMixerCoverage.inspect(flinger, policy, 27)
        assertTrue(snapshot.known)
        assertTrue(snapshot.targets.isEmpty())
    }
    @Test fun baselineIsSelectedByExactEffectIdNotFirstOutput() {
        assertTrue(inspect(globalOutput = 99, suspended = true).known)
        val flinger = output(13, emptyList(), listOf(effect(28, 0)))
        val policy = " Outputs (1):\n" + policyOutput(13, emptyList())
        assertFalse(GlobalMixerCoverage.inspect(flinger, policy, 27).known)
    }
    @Test fun mismatchedPolicyIdentityFailsClosed() {
        val flinger = output(13, emptyList(), listOf(effect(27, 0))) + output(21, listOf(Playback()))
        val base = " Outputs (2):\n" + policyOutput(13, emptyList())
        listOf(Playback(uid = 10323), Playback(port = 71), Playback(session = 362)).forEach {
            val policy = base + policyOutput(21, listOf(it)) +
                "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 13; Session: 0;\n"
            assertFalse(GlobalMixerCoverage.inspect(flinger, policy, 27).known)
        }
    }
    @Test fun malformedMissingAndOversizedDumpsFailClosed() {
        assertFalse(GlobalMixerCoverage.inspect("", "", 27).known)
        assertFalse(GlobalMixerCoverage.inspect("x".repeat(GlobalMixerCoverage.MAX_DUMP_CHARS + 1), "", 27).known)
        assertFalse(GlobalMixerCoverage.inspect(output(13, emptyList(), listOf(effect(27, 0))).substringBefore("Effect ID"), "", 27).known)
        assertFalse(GlobalMixerCoverage.inspect("", "", -1).known)
    }
    @Test fun countAndBudgetAreBoundedByPlaybackIdentity() {
        val tracks = (1..12).map { Playback(session = it, port = it) }
        assertEquals(GlobalMixerCoverage.MAX_SUPPLEMENTAL_SESSIONS, inspect(tracks).targets.size)
        val budget = SupplementalAttemptBudget()
        repeat(3) { assertTrue(budget.mayAttempt("a")); budget.record("a") }
        assertFalse(budget.mayAttempt("a"))
        assertTrue(budget.mayAttempt("b"))
        budget.retain(setOf("b"))
        assertTrue(budget.mayAttempt("a"))
        budget.record("b")
        budget.clear()
        assertTrue(budget.mayAttempt("b"))
    }
}
