package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class GlobalOutputCoverageTest {
    private fun output(id: Int, active: Boolean, effect: Boolean = false, type: String = "MIXER", flags: String = "000") = """
Output thread 0x1, name AudioOut_Test, tid 10, type 0 ($type):
  I/O handle: $id
  Standby: ${if (active) "no" else "yes"}
  ${if (active) "1 Tracks of which 1 are active" else "0 Tracks"}
  ${if (active) "11 yes 100/ 200 31 41 A 0x$flags 00000001 00000003 44100" else ""}
  ${if (effect) "1" else "0"} Effect Chains
  ${if (effect) """
    1 effects for session 0
    Effect ID 9:
        Session State Registered Internal Enabled Suspended:
        00000 002 y n y n
        Descriptor:
        - UUID: f27317f4-c984-4de6-9a90-545759495bf2
        - name: LightningDSP
""" else ""}
  Local log:
  99 yes 101/ 201 32 42 A 0x004 historical row
""".trimIndent()
    private fun policy(active: Set<Int> = setOf(1), effectOutput: Int = 2, special: String = "") = """
 Outputs (2):
  1. Port ID: 1; I/O handle: 1; IOProfile name:normal; Latency: 20; 0x0006 (AUDIO_OUTPUT_FLAG_PRIMARY|AUDIO_OUTPUT_FLAG_FAST$special)
     Global active count: ${if (1 in active) 1 else 0}
     ${if (1 in active) "Port ID: 41; Session ID: 31; uid 200; State: Active" else ""}
  2. Port ID: 2; I/O handle: 2; IOProfile name:deep; Latency: 100; 0x0008 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)
     Global active count: ${if (2 in active) 1 else 0}
     ${if (2 in active) "Port ID: 41; Session ID: 31; uid 200; State: Active" else ""}
 Inputs (0):
 - Effects (1):
  1. Effect ID: 9; Attached to I/O handle: $effectOutput; Session: 0;
     Music Effect? yes; "Vendor display name"; Enabled; Active
""".trimIndent().let { " $it" }
    private fun inspect(flinger: String = output(1, true) + "\n" + output(2, false, true), policy: String = policy()) =
        GlobalOutputCoverage.inspect(flinger, policy)

    @Test fun identifiesIdleGlobalOutputAndNormalActiveTrackDespiteFastCapableOutput() {
        val snapshot = inspect()
        assertEquals(GlobalOutputCoverage.State.MISMATCH, snapshot.state)
        assertEquals(1, snapshot.activeOutput)
        assertEquals(2, snapshot.effectOutput)
        assertEquals("2->1:200:31:41", snapshot.signature)
    }
    @Test fun coveredMeansAttachmentNotAudibleProcessing() {
        val snapshot = inspect(output(1, true, true) + "\n" + output(2, false), policy(effectOutput = 1))
        assertEquals(GlobalOutputCoverage.State.COVERED, snapshot.state)
        assertTrue(snapshot.reason.contains("processing is not inferred"))
    }
    @Test fun actualFastTrackIsUnsupported() {
        assertEquals(GlobalOutputCoverage.State.UNSUPPORTED, inspect(output(1, true).replace("11 yes", "F1 11 yes") + "\n" + output(2, false, true)).state)
    }
    @Test fun cblkFlagsAreNotOutputFlagsAndDoNotMakeANormalTrackFast() {
        assertEquals(GlobalOutputCoverage.State.MISMATCH, inspect(output(1, true, flags = "004") + "\n" + output(2, false, true)).state)
    }
    @Test fun activeFastMixerSlotsPreventRebindingEvenIfTheRowTypeIsMissing() {
        val dump = output(1, true).replace("  1 Tracks", "  FastMixer thread 0x1\n  Fast tracks: sMaxFastTracks=12 activeMask=0x3\n  1 Tracks") + "\n" + output(2, false, true)
        assertEquals(GlobalOutputCoverage.State.UNSUPPORTED, inspect(dump).state)
    }
    @Test fun directMmapRawAndOffloadAreNotRecoveryTargets() {
        for (flag in listOf("RAW", "DIRECT", "MMAP_NOIRQ", "COMPRESS_OFFLOAD", "BIT_PERFECT")) {
            assertEquals(flag, GlobalOutputCoverage.State.UNSUPPORTED, inspect(policy = policy(special = "|AUDIO_OUTPUT_FLAG_$flag")).state)
        }
        assertEquals(GlobalOutputCoverage.State.UNSUPPORTED, inspect(output(1, true, type = "DIRECT") + "\n" + output(2, false, true)).state)
    }
    @Test fun idleAndConcurrentOutputsDoNotTriggerRecovery() {
        assertEquals(GlobalOutputCoverage.State.IDLE, inspect(output(1, false) + "\n" + output(2, false, true), policy(emptySet())).state)
        assertEquals(GlobalOutputCoverage.State.MULTIPLE_OUTPUTS, inspect(output(1, true) + "\n" + output(2, true, true), policy(setOf(1, 2))).state)
    }
    @Test fun contradictorySnapshotsAreInconclusive() {
        assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect(policy = policy(emptySet())).state)
        assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect(policy = policy().replace("Port ID: 41; Session", "Port ID: 42; Session")).state)
        assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect(policy = policy(effectOutput = 1)).state)
    }
    @Test fun missingDisabledSuspendedAndMultipleEffectsDoNotClaimCoverage() {
        val normal = output(1, true) + "\n" + output(2, false, true)
        for (bad in listOf(normal.replace("y n y n", "y n n n"), normal.replace("y n y n", "y n y y"), normal.replace("00000 002", "00031 002"), output(1, true) + "\n" + output(2, false))) {
            assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect(bad).state)
        }
        assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect(output(1, true, true) + "\n" + output(2, false, true)).state)
    }
    @Test fun malformedTruncatedAndOversizedDumpsFailClosed() {
        for (bad in listOf("", "Permission denied", output(1, true).replace("1 Tracks of which 1", "2 Tracks of which 1"))) {
            assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect(bad).state)
        }
        assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect(policy = "Permission denied").state)
        assertEquals(GlobalOutputCoverage.State.INCONCLUSIVE, inspect("x".repeat(2_000_001)).state)
    }
    @Test fun historicalRowsAreIgnoredAndChangedPlaybackGetsNewIdentity() {
        val snapshot = inspect()
        val changed = inspect((output(1, true) + "\n" + output(2, false, true)).replace("200 31 41", "200 33 41"))
        assertNotEquals(snapshot.signature, changed.signature)
    }
    @Test fun inactiveFastMixerWithoutTrackTableDoesNotHideRealMismatch() {
        val idle = output(2, false, true).replace("  0 Tracks", "  FastMixer thread 0x1 COLD_IDLE\n  0 Tracks")
        assertEquals(GlobalOutputCoverage.State.MISMATCH, inspect(output(1, true) + "\n" + idle).state)
    }
}
