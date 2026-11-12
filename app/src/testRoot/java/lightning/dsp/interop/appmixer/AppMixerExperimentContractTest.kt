package lightning.dsp.interop.appmixer

import org.junit.Assert.*
import org.junit.Test

class AppMixerExperimentContractTest {
    private fun args(uid: String = "10322", seconds: String = "30") = arrayOf(
        "--manual-routing-test", "--uids=$uid", "--seconds=$seconds", "--acknowledge-audio-interruption")

    @Test fun explicitAppUidsAreRequiredAndDeduplicated() {
        assertEquals(setOf(10322, 110654), AppMixerExperimentConfig.parse(args("10322,110654,10322")).uids)
        for (uids in listOf("0", "1000", "2000", "99000", "10322,0", "", "-89678", "not-a-uid", "2147483648", List(17) { "10322" }.joinToString(","))) {
            assertThrows(IllegalArgumentException::class.java) { AppMixerExperimentConfig.parse(args(uids)) }
        }
    }
    @Test fun invocationIsOptInAndTimeBounded() {
        assertThrows(IllegalArgumentException::class.java) { AppMixerExperimentConfig.parse(emptyArray()) }
        assertThrows(IllegalArgumentException::class.java) { AppMixerExperimentConfig.parse(args().dropLast(1).toTypedArray()) }
        for (seconds in listOf("0", "4", "91", "2147483647")) {
            assertThrows(IllegalArgumentException::class.java) { AppMixerExperimentConfig.parse(args(seconds = seconds)) }
        }
    }
    @Test fun partialWritesDrainBeforeNextCaptureAndPreserveSamples() {
        var running = true
        var reads = 0
        val output = mutableListOf<Short>()
        val counts = AppMixerPcmRelay.run(ShortArray(4), { running }, { buffer ->
            reads++
            assertEquals(1, reads)
            shortArrayOf(1,2,3,4).copyInto(buffer)
            4
        }, { block, offset, count ->
            val written = minOf(2, count)
            output.addAll(block.slice(offset until offset + written))
            if (output.size == 4) running = false
            written
        }, { fail("No idle expected") })
        assertEquals(listOf<Short>(1,2,3,4), output)
        assertEquals(AppMixerPcmRelay.Counts(4,4), counts)
    }
    @Test fun emptyReadsAndBackpressureDoNotSpinOrAllocateMoreAudio() {
        var reads = 0
        var idles = 0
        val result = AppMixerPcmRelay.run(ShortArray(2), { idles < 3 }, { reads++; 0 },
            { _,_,_ -> fail("Must not write an empty capture"); 0 }, { idles++ })
        assertEquals(3, reads)
        assertEquals(AppMixerPcmRelay.Counts(0,0), result)
    }
    @Test fun negativeCaptureAndPlaybackStatusesFailInsteadOfReplayingStaleAudio() {
        assertThrows(IllegalStateException::class.java) {
            AppMixerPcmRelay.run(ShortArray(2), { true }, { -6 }, { _,_,_ -> 0 }, {})
        }
        assertThrows(IllegalStateException::class.java) {
            AppMixerPcmRelay.run(ShortArray(2), { true }, { 2 }, { _,_,_ -> -6 }, {})
        }
    }
    @Test fun cancellationInterruptsBackpressure() {
        var running = true
        var reads = 0
        val counts = AppMixerPcmRelay.run(ShortArray(2), { running }, { reads++; 2 },
            { _,_,_ -> 0 }, { running = false })
        assertEquals(1, reads)
        assertEquals(AppMixerPcmRelay.Counts(2,0), counts)
    }
    @Test fun stalledPlaybackFailsAndPartialProgressResetsTheDeadline() {
        var clock = 0L
        var writes = 0
        assertThrows(IllegalStateException::class.java) {
            AppMixerPcmRelay.run(ShortArray(2), { true }, { 2 }, { _, _, _ ->
                writes++
                if (writes == 2) 1 else 0
            }, { clock += 6 }, { clock }, 10)
        }
        // The second write progresses at t=6; the stall expires at t=18,
        // not t=12 from the start of the block.
        assertEquals(5, writes)
        assertEquals(18L, clock)
    }

    @Test fun ownerCommandsAreBoundedAndRequireCompleteLines() {
        assertEquals("READY", AppMixerControlProtocol.readCommand("READY\n".reader()))
        assertEquals("STOP", AppMixerControlProtocol.readCommand("STOP\n".reader()))
        assertNull(AppMixerControlProtocol.readCommand("READY".reader()))
        assertNull(AppMixerControlProtocol.readCommand(("x".repeat(17) + "\n").reader()))
        assertEquals("x".repeat(200), AppMixerControlProtocol.readCommand(("x".repeat(200) + "\n").reader(), 256))
        assertNull(AppMixerControlProtocol.readCommand(("x".repeat(257) + "\n").reader(), 256))
        assertNull(AppMixerControlProtocol.readCommand("READY\r\n".reader()))
    }

    @Test fun supervisedUidSetIsBoundedSeparatelyFromManualTest() {
        val limit = AppMixerExperimentConfig.MAX_SUPERVISED_UIDS
        val many = (10000 until 10000 + limit).joinToString(",")
        assertEquals(limit, AppMixerExperimentConfig.parseUids("--uids=$many", limit).size)
        assertEquals(651, AppMixerExperimentConfig.parseUids("--uids=" + (10000..10650).joinToString(","), limit).size)
        assertThrows(IllegalArgumentException::class.java) {
            AppMixerExperimentConfig.parseUids("--uids=$many,11024", limit)
        }
    }

    @Test fun exclusionsRequireRootAndFitNativeCriterionLimit() {
        assertEquals(setOf(0, 10001, 10322), AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=0,10001,10322"))
        assertThrows(IllegalArgumentException::class.java) { AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=10001") }
        assertThrows(IllegalArgumentException::class.java) {
            AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=0," + (10001..10017).joinToString(","))
        }
    }

    @Test fun pixelAndroid17AudioFlingerEffectChainDumpIsAccepted() {
        val dump = """
            Start begin: 11-11 15:45:34.833
            Output thread 0xb400007c44f8a790, name AudioOut_D, tid 4673, type 0 (MIXER):
              0 Effect Chains
            Output thread 0xb400007c44d60790, name AudioOut_15, tid 4701, type 0 (MIXER):
              1 Effect Chains
                Effect ID 19:
                  UUID: ${AppMixerEffectInventory.EFFECT_UUID}
        """.trimIndent()
        assertEquals(1, AppMixerEffectInventory.count(dump))
        assertEquals(0, AppMixerEffectInventory.count(dump.substringBefore("UUID:")))
    }

    @Test fun unknownOrIncompleteEffectInventoryFailsClosed() {
        assertNull(AppMixerEffectInventory.count("Start begin: today\nAudio service unavailable"))
        assertNull(AppMixerEffectInventory.count(""))
    }

}
