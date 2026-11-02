package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

/** Sanitized Android dump layout: output FAST is not a fast AudioTrack. */
class GlobalMixerCoverageTest {
    private data class Playback(val session: Int = 361, val uid: Int = 10322, val port: Int = 70,
                                val type: String = "", val format: String = "00000001",
                                val channels: String = "00000003", val flags: String = "000", val policyFlags: String = "0", val policyChannels: String = "3", val attributeFlags: String = "0",
                                val bitPerfect: Boolean = false, val suffix: String = "false false 129.05 t 0 0 0 0 1",
                                val extended: Boolean = true, val active: Boolean = true,
                                val state: String = if (active) "A" else "P")
    private fun effect(id: Int, session: Int, suspended: Boolean = false) = """
        Effect ID $id:
          Session State Registered Internal Enabled Suspended:
          $session 003 y n y ${if (suspended) "y" else "n"}
          - UUID: f27317f4-c984-4de6-9a90-545759495bf2
    """.trimIndent()
    private fun output(id: Int, tracks: List<Playback>, effects: List<String> = emptyList(), type: String = "MIXER") = buildString {
        append("Output thread 0x$id, name AudioOut, tid 100, type 0 ($type):\n")
        append("  I/O handle: $id\n  Standby: ${if (tracks.none { it.active }) "yes" else "no"}\n")
        append("  ${tracks.size} Tracks${if (tracks.isEmpty()) "" else " of which ${tracks.count { it.active }} are active"}\n")
        append("    Type Id Active Client(pid/uid) Session Port Id S Flags Format Chn mask SRate ST Usg CT G db L dB R dB VS dB PortVol dB PortMuted Server FrmCnt FrmRdy F Underruns Flushed BitPerfect InternalMute Latency\n")
        if (tracks.firstOrNull()?.extended != false) append(" Spat Meta Sce Silent Top\n")
        tracks.forEachIndexed { index, t ->
            append("    ${t.type} ${index + 1} ${if (t.active) "yes" else "no"} 9716/ ${t.uid} ${t.session} ${t.port} ${t.state} 0x${t.flags} ${t.format} ${t.channels} 44100 3 1 2 -inf 0 0 0 -inf false 0000 12376 12376 A 0 0 ${if (t.bitPerfect) t.suffix.replaceFirst("false", "true") else t.suffix}\n")
        }
        append("  ${effects.size} Effect Chains\n")
        effects.forEach { append("  1 effects for session 0\n$it\n") }
    }
    private fun policyOutput(id: Int, tracks: List<Playback>, flags: String = "108", usage: String = "MEDIA") = buildString {
        append("  1. Port ID: $id; I/O handle: $id; IOProfile name: playback; 0x$flags (FLAGS)\n")
        append("    Global active count: ${tracks.count { it.active }}\n")
        tracks.forEachIndexed { index, t ->
            append("    ${index + 1}. Port ID: ${t.port}; Session ID: ${t.session}; uid ${t.uid}; State: ${if (t.active) "Active" else "Inactive"}\n")
            append("      Format: AUDIO_FORMAT_PCM_16_BIT; 1; Channel mask: 0x${t.policyChannels}\n")
            append("      Attributes: { Content type: AUDIO_CONTENT_TYPE_UNKNOWN Usage: AUDIO_USAGE_$usage Source: AUDIO_SOURCE_INVALID Flags: 0x${t.attributeFlags} Tags:  }\n")
            append("      Stream: 3; Flags: 0x${t.policyFlags}; Usage: AUDIO_USAGE_$usage\n")
        }
    }
    private fun inspect(tracks: List<Playback> = listOf(Playback()), globalOutput: Int = 13,
                        suspended: Boolean = false, extra: List<String> = emptyList(), owned: Set<Int> = emptySet(),
                        flags: String = "104", type: String = "MIXER", usage: String = "MEDIA",
                        transformFlinger: (String) -> String = { it },
                        transformPolicy: (String) -> String = { it }):
        GlobalMixerCoverage.Snapshot {
        val baseline = output(globalOutput, emptyList(), listOf(effect(27, 0, suspended)))
        val flinger = baseline + output(21, tracks, extra, type)
        val policy = " Outputs (2):\n" + policyOutput(globalOutput, emptyList()) + policyOutput(21, tracks, flags, usage) +
            "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: $globalOutput; Session: 0;\n"
        return GlobalMixerCoverage.inspect(transformFlinger(flinger), transformPolicy(policy), 27, owned)
    }

    private fun inspectSessionMode(tracks: List<Playback> = listOf(Playback()), effects: List<String> = emptyList(), usage: String = "MEDIA"):
        GlobalMixerCoverage.Snapshot {
        val flinger = output(45, tracks, effects)
        val policy = " Outputs (1):\n" + policyOutput(45, tracks, usage = usage) + "\n Inputs (0):\n"
        return GlobalMixerCoverage.inspect(flinger, policy, null)
    }

    @Test fun normalRawFastOutputIsEligibleEvenAtZeroVolume() {
        val snapshot = inspect()
        assertTrue(snapshot.reason, snapshot.known)
        assertEquals(listOf(361), snapshot.targets.map { it.id })
        assertEquals(13, snapshot.globalOutput)
    }
    @Test fun sessionModeDiscoversUncoveredPlaybackWithoutCreatingGlobalSessionZero() {
        val snapshot = inspectSessionMode()
        assertTrue(snapshot.reason, snapshot.known)
        assertNull(snapshot.globalOutput)
        assertEquals(listOf(361), snapshot.targets.map { it.id })
    }
    @Test fun sessionModeStillRecognizesMonoUnknownWhatsAppMessageSessions() {
        val snapshot = inspectSessionMode(listOf(Playback(
            channels = "00000001", policyChannels = "1", attributeFlags = "800"
        )), usage = "UNKNOWN")
        assertTrue(snapshot.reason, snapshot.known)
        assertEquals(listOf(361), snapshot.targets.map { it.id })
    }
    @Test fun sessionModeWithoutBaselineFailsClosedWhenSessionZeroEffectAppears() {
        val snapshot = inspectSessionMode(effects = listOf(effect(27, 0)))
        assertFalse(snapshot.known)
    }
    @Test fun latencyWithoutSpatialColumnsIsAlsoEligible() {
        val snapshot = inspect(listOf(Playback(suffix = "false false 455.17", extended = false)))
        assertTrue(snapshot.reason, snapshot.known)
        assertEquals(1, snapshot.targets.size)
    }
    @Test fun malformedBitPerfectAndUnknownTrailingLayoutsAreExcluded() {
        listOf(
            "unknown false 129.05 t 0 0 0 0 1",
            "false unknown 129.05 t 0 0 0 0 1",
            "false false 129.05 t 0 0 0 0",
            "false false 129.05 t 0 0 0 0 1 extra",
            "false false false 129.05 t 0 0 0 0 1",
            "true false 129.05 t 0 0 0 0 1",
        ).forEach { suffix ->
            val snapshot = inspect(listOf(Playback(suffix = suffix)))
            assertTrue(snapshot.reason, snapshot.known)
            assertTrue("Malformed/unsafe row must not become a target", snapshot.targets.isEmpty())
        }
    }
    @Test fun shiftedOrUnknownHeaderColumnsAreExcluded() {
        listOf(
            "Flushed BitPerfect InternalMute" to "Flushed InternalMute BitPerfect",
            "ST Usg CT" to "ST Unknown Usg CT",
            "Spat Meta Sce Silent Top" to "Spat Meta Unknown Silent Top",
        ).forEach { (from, to) ->
            val snapshot = inspect(transformFlinger = { it.replace(from, to) })
            assertTrue(snapshot.reason, snapshot.known)
            assertTrue(snapshot.targets.isEmpty())
        }
    }
    @Test fun knownLegacyHeaderWithoutBitPerfectIsStillEligible() {
        val snapshot = inspect(
            tracks = listOf(Playback(suffix = "false 455.17", extended = false)),
            transformFlinger = { it.replace("Flushed BitPerfect InternalMute", "Flushed InternalMute") })
        assertTrue(snapshot.reason, snapshot.known)
        assertEquals(1, snapshot.targets.size)
    }
    @Test fun deepBufferAndGameAreEligible() {
        assertEquals(1, inspect(flags = "8", usage = "GAME").targets.size)
    }
    @Test fun actualFastTrackIsExcluded() { assertTrue(inspect(listOf(Playback(type = "F"))).targets.isEmpty()) }
    @Test fun nonzeroTrackFlagsAreExcluded() { assertTrue(inspect(listOf(Playback(flags = "001"))).targets.isEmpty()) }
    @Test fun bitPerfectTrackIsExcluded() { assertTrue(inspect(listOf(Playback(bitPerfect = true))).targets.isEmpty()) }
    @Test fun noTargetDiagnosticsDistinguishTrackAndOutputGuardsWithoutIdentities() {
        val track = inspect(listOf(Playback(flags = "001")))
        val output = inspect(flags = "1000")
        assertTrue(track.reason, track.reason.contains("track control-block guards=1"))
        assertTrue(output.reason, output.reason.contains("output type/flags guards=1"))
        listOf(track, output).forEach { snapshot ->
            assertFalse(snapshot.reason.contains("361"))
            assertFalse(snapshot.reason.contains("10322"))
        }
        assertTrue(inspect(tracks = emptyList()).reason.contains("no active sessions"))
    }
    @Test fun specialAndUnknownOutputFlagsAreExcluded() {
        listOf("1", "10", "1000", "4000", "40000", "80000000").forEach {
            assertTrue("flags=$it", inspect(flags = it).targets.isEmpty())
        }
    }
    @Test fun specialOutputTypesAreExcluded() {
        listOf("DIRECT", "OFFLOAD", "MMAP", "UNKNOWN").forEach { assertTrue(inspect(type = it).targets.isEmpty()) }
    }
    @Test fun whatsappMonoVoiceMessageProfileIsEligibleButUnknownVariantsAreNot() {
        val voiceMessage = Playback(channels = "00000001", policyChannels = "1", attributeFlags = "800")
        val snapshot = inspect(listOf(voiceMessage), usage = "UNKNOWN")
        assertTrue(snapshot.reason, snapshot.known)
        assertEquals(listOf(361), snapshot.targets.map { it.id })
        assertTrue(inspect(listOf(voiceMessage), usage = "UNKNOWN",
            transformFlinger = { it.replace("00000001 00000001 44100", "00000001 00000002 44100") }).targets.isEmpty())
        assertTrue(inspect(listOf(voiceMessage.copy(attributeFlags = "810")), usage = "UNKNOWN").targets.isEmpty())
        assertTrue(inspect(listOf(voiceMessage.copy(channels = "00000003", policyChannels = "3")),
            usage = "UNKNOWN").targets.isEmpty())
        assertTrue(inspect(listOf(voiceMessage), usage = "VOICE_COMMUNICATION").targets.isEmpty())
    }
    @Test fun unsupportedFormatChannelsAndUsageAreExcluded() {
        assertTrue(inspect(listOf(Playback(format = "08000000"))).targets.isEmpty())
        assertTrue(inspect(listOf(Playback(channels = "00000005", policyChannels = "5"))).targets.isEmpty())
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
    @Test fun unknownDiagnosticsIdentifyTheFailedPhaseWithoutDumpContent() {
        val baseline = output(13, emptyList(), listOf(effect(27, 0)))
        val validPolicy = " Outputs (1):\n" + policyOutput(13, emptyList()) +
            "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 13; Session: 0;\n"
        val privateMarker = "private-diagnostic-marker"
        val cases = listOf(
            GlobalMixerCoverage.inspect(privateMarker, validPolicy, 27) to
                "AudioFlinger diagnostic layout is incomplete or unsupported",
            GlobalMixerCoverage.inspect(baseline.replace("Standby: yes", "Standby: $privateMarker"), validPolicy, 27) to
                "AudioFlinger diagnostic layout is incomplete or unsupported",
            GlobalMixerCoverage.inspect(baseline, privateMarker, 27) to
                "AudioPolicy diagnostic layout is incomplete or unsupported",
            GlobalMixerCoverage.inspect(baseline, validPolicy.replace("Global active count: 0", "Global active count: $privateMarker"), 27) to
                "AudioPolicy diagnostic layout is incomplete or unsupported",
            GlobalMixerCoverage.inspect(baseline, validPolicy, 28) to
                "Audio topology or baseline attachment identity is inconsistent or missing",
            inspect(transformFlinger = { it.replace(" 9716/ 10322 ", " 9716/ 10323 ") }) to
                "Audio topology or baseline attachment identity is inconsistent or missing",
            GlobalMixerCoverage.inspect("x".repeat(GlobalMixerCoverage.MAX_DUMP_CHARS + 1), validPolicy, 27) to
                "Audio diagnostic size limit exceeded",
            GlobalMixerCoverage.inspect(baseline, "x".repeat(GlobalMixerCoverage.MAX_DUMP_CHARS + 1), 27) to
                "Audio diagnostic size limit exceeded",
            GlobalMixerCoverage.inspect(baseline, validPolicy, 0) to "Invalid global effect identity",
        )
        cases.forEach { (snapshot, expected) ->
            assertFalse(snapshot.known)
            assertEquals(expected, snapshot.reason)
            assertFalse(snapshot.reason.contains(privateMarker))
            assertTrue(snapshot.targets.isEmpty())
        }
    }

    @Test fun pausedOwnedSessionIsRetainedAndResumeNeedsNoNewInstance() {
        val active = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        val paused = inspect(tracks = listOf(Playback(active = false, flags = "600")), extra = listOf(effect(31, 361)), owned = setOf(31))
        val session = active.targets.single()
        assertTrue(paused.reason, paused.known)
        assertTrue(paused.targets.isEmpty())
        assertTrue(paused.retainsAttachment(session, 31))
        assertEquals(active.signature, paused.signature)
        val resumed = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        assertEquals(session, resumed.targets.single())
        assertTrue(resumed.retainsAttachment(session, 31))
        assertEquals(31, resumed.effects.single { it.session == 361 }.id)
        // This is the same production predicate used by the controller's retirement.
        assertTrue(listOf(active, paused, resumed).all { it.retainsAttachment(session, 31) })
    }
    @Test fun inactiveUnownedSessionNeverCreatesAnEffect() {
        val snapshot = inspect(tracks = listOf(Playback(active = false)))
        assertTrue(snapshot.reason, snapshot.known)
        assertTrue(snapshot.targets.isEmpty())
        assertTrue(snapshot.retainableSessions.isEmpty())
        assertFalse(snapshot.retainsAttachment(GlobalMixerCoverage.Session(361, 10322, 21, setOf(70)), 31))
    }
    @Test fun ownedActivityMismatchIsRetentionOnlyNotCreation() {
        val active = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        val session = active.targets.single()
        val pausedPolicy = inspect(extra = listOf(effect(31, 361)), owned = setOf(31),
            transformPolicy = { it.replace("Global active count: 1", "Global active count: 0").replace("State: Active", "State: Inactive") })
        assertTrue(pausedPolicy.reason, pausedPolicy.known)
        assertTrue(pausedPolicy.targets.isEmpty())
        assertTrue(pausedPolicy.retainsAttachment(session, 31))
        assertEquals(active.signature, pausedPolicy.signature)
        val pausedTrack = inspect(tracks = listOf(Playback(active = false)), extra = listOf(effect(31, 361)), owned = setOf(31),
            transformPolicy = { it.replace("Global active count: 0\n    1.", "Global active count: 1\n    1.").replace("State: Inactive", "State: Active") })
        assertTrue(pausedTrack.reason, pausedTrack.known)
        assertTrue(pausedTrack.targets.isEmpty())
        assertTrue(pausedTrack.retainsAttachment(session, 31))
        assertEquals(active.signature, pausedTrack.signature)
    }
    @Test fun unownedActivityMismatchStillFailsClosed() {
        val snapshot = inspect(transformPolicy = {
            it.replace("Global active count: 1", "Global active count: 0").replace("State: Active", "State: Inactive") })
        assertFalse(snapshot.known)
    }
    @Test fun inactiveOwnershipIsWithdrawnForChangedIdentityOrUnsafePath() {
        val original = inspect(extra = listOf(effect(31, 361)), owned = setOf(31)).targets.single()
        val paused = Playback(active = false)
        val cases = listOf(
            inspect(tracks = listOf(paused.copy(uid = 10323)), extra = listOf(effect(31, 361)), owned = setOf(31)),
            inspect(tracks = listOf(paused.copy(port = 71)), extra = listOf(effect(31, 361)), owned = setOf(31)),
            inspect(tracks = listOf(paused.copy(session = 362)), extra = listOf(effect(31, 361)), owned = setOf(31)),
            inspect(tracks = listOf(paused.copy(type = "F")), extra = listOf(effect(31, 361)), owned = setOf(31)),
            inspect(tracks = listOf(paused.copy(bitPerfect = true)), extra = listOf(effect(31, 361)), owned = setOf(31)),
            inspect(tracks = listOf(paused), extra = listOf(effect(31, 361)), owned = setOf(31), flags = "1"),
            inspect(tracks = emptyList(), extra = listOf(effect(31, 361)), owned = setOf(31)),
            inspect(tracks = listOf(paused), extra = listOf(effect(31, 361)), owned = setOf(31),
                transformPolicy = { it.replace("uid 10322", "uid 10323") }),
            inspect(tracks = listOf(paused), extra = listOf(effect(31, 361)), owned = setOf(31),
                transformPolicy = { it.replace("I/O handle: 21", "I/O handle: 22") }),
            GlobalMixerCoverage.Snapshot(false, "unknown"),
        )
        cases.forEach { assertFalse(it.reason, it.retainsAttachment(original, 31)) }
    }
    @Test fun pausedControlBlockBitsAreOwnedRetentionOnly() {
        val original = inspect(extra = listOf(effect(31, 361)), owned = setOf(31)).targets.single()
        listOf("000", "200", "400", "600").forEach { flags ->
            val paused = inspect(tracks = listOf(Playback(active = false, flags = flags)),
                extra = listOf(effect(31, 361)), owned = setOf(31))
            assertTrue(paused.reason, paused.known)
            assertTrue(paused.retainsAttachment(original, 31))
            assertTrue(paused.targets.isEmpty())
        }
        listOf("004", "008", "010", "601", "800", "ffffffff").forEach { flags ->
            val paused = inspect(tracks = listOf(Playback(active = false, flags = flags)),
                extra = listOf(effect(31, 361)), owned = setOf(31))
            assertFalse(flags, paused.retainsAttachment(original, 31))
        }
        val unowned = inspect(tracks = listOf(Playback(active = false, flags = "600")))
        assertTrue(unowned.targets.isEmpty())
        assertTrue(unowned.retainableSessions.isEmpty())
        val wrongState = inspect(tracks = listOf(Playback(active = false, flags = "600", state = "A")),
            extra = listOf(effect(31, 361)), owned = setOf(31))
        assertFalse(wrongState.retainsAttachment(original, 31))
        val active = inspect(tracks = listOf(Playback(flags = "600")),
            extra = listOf(effect(31, 361)), owned = setOf(31))
        assertFalse(active.retainsAttachment(original, 31))
    }
    @Test fun policyActiveClientCannotHideBehindEmptyFlingerTrackList() {
        val paused = listOf(Playback(active = false, flags = "600"))
        val flinger = output(13, emptyList(), listOf(effect(27, 0))) + output(21, paused, listOf(effect(31, 361)))
        val policy = " Outputs (2):\n" + policyOutput(13, listOf(Playback(session = 999, port = 99))) +
            policyOutput(21, paused) + "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 13; Session: 0;\n"
        val snapshot = GlobalMixerCoverage.inspect(flinger, policy, 27, setOf(31))
        assertFalse(snapshot.known)
        assertTrue(snapshot.targets.isEmpty())
        assertTrue(snapshot.retainableSessions.isEmpty())
    }
    @Test fun ownedActivityExceptionDoesNotApplyToAnotherOutput() {
        val paused = listOf(Playback(active = false, flags = "600"))
        val flinger = output(13, emptyList(), listOf(effect(27, 0))) +
            output(21, paused, listOf(effect(31, 361))) + output(22, emptyList())
        val policy = " Outputs (3):\n" + policyOutput(13, emptyList()) + policyOutput(21, paused) +
            policyOutput(22, listOf(Playback(port = 71))) +
            "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 13; Session: 0;\n"
        val snapshot = GlobalMixerCoverage.inspect(flinger, policy, 27, setOf(31))
        assertFalse(snapshot.known)
        assertTrue(snapshot.targets.isEmpty())
        assertTrue(snapshot.retainableSessions.isEmpty())
    }
    @Test fun missingPolicyDescriptorForFlingerOutputFailsClosed() {
        val flinger = output(13, emptyList(), listOf(effect(27, 0))) + output(21, emptyList())
        val policy = " Outputs (1):\n" + policyOutput(13, emptyList()) +
            "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 13; Session: 0;\n"
        val snapshot = GlobalMixerCoverage.inspect(flinger, policy, 27)
        assertFalse(snapshot.known)
    }
    @Test fun inactiveOwnershipRequiresBothCompleteSourceIdentities() {
        val original = inspect(extra = listOf(effect(31, 361)), owned = setOf(31)).targets.single()
        val transforms: List<(String) -> String> = listOf(
            { it.substringBefore(" Outputs (") },
            { it.replace("State: Inactive", "State: Unknown") },
            { it.replace("Session ID: 361", "Session ID: 362") },
            { it.replace("Port ID: 70; Session", "Port ID: 71; Session") },
            { it.replace("Usage: AUDIO_USAGE_MEDIA", "Usage: AUDIO_USAGE_VOICE_COMMUNICATION") },
            { it.replace("Channel mask: 0x3", "Channel mask: 0x5") },
        )
        transforms.forEach { transform ->
            val snapshot = inspect(tracks = listOf(Playback(active = false)), extra = listOf(effect(31, 361)),
                owned = setOf(31), transformPolicy = transform)
            assertFalse(snapshot.reason, snapshot.retainsAttachment(original, 31))
        }
    }
    @Test fun inactiveSessionOnGlobalOutputIsNeverRetainedEvenWhenGlobalSuspended() {
        val track = listOf(Playback(active = false))
        val flinger = output(21, track, listOf(effect(27, 0, true), effect(31, 361)))
        val policy = " Outputs (1):\n" + policyOutput(21, track) +
            "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 21; Session: 0;\n"
        val snapshot = GlobalMixerCoverage.inspect(flinger, policy, 27, setOf(31))
        assertTrue(snapshot.reason, snapshot.known)
        assertTrue(snapshot.retainableSessions.isEmpty())
        assertTrue(snapshot.targets.isEmpty())
    }
    @Test fun unsafeOutputAndEligibilityChangesCannotShareStableSignature() {
        val safe = inspect(flags = "104")
        val direct = inspect(flags = "1")
        val unknown = inspect(type = "UNKNOWN")
        val incompatible = inspect(usage = "VOICE_COMMUNICATION")
        assertEquals(1, safe.targets.size)
        listOf(direct, unknown, incompatible).forEach {
            assertTrue(it.targets.isEmpty())
            assertNotEquals(safe.signature, it.signature)
        }
    }
    @Test fun ownedPausedTransitionFlagsRetainButNeverCreate() {
        val foreground = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        val owner = GlobalMixerCoverage.OwnedAttachment(foreground.targets.single(), 31, true)
        val transition = inspect(tracks = listOf(Playback(active = true, state = "P", flags = "200")),
            extra = listOf(effect(31, 361)), owned = setOf(31))
        val paused = inspect(tracks = listOf(Playback(active = false, flags = "600")),
            extra = listOf(effect(31, 361)), owned = setOf(31))
        listOf(foreground, transition, paused, foreground).forEach { snapshot ->
            assertTrue(snapshot.reason, snapshot.retainsAttachment(owner.session, 31))
            assertTrue(GlobalMixerCoverage.reconcile(snapshot, listOf(owner)).obsolete.isEmpty())
        }
        assertTrue(transition.targets.isEmpty())
        assertTrue(inspect(tracks = listOf(Playback(active = true, state = "P", flags = "200"))).targets.isEmpty())
        assertFalse(inspect(tracks = listOf(Playback(active = true, state = "P", flags = "204")),
            extra = listOf(effect(31, 361)), owned = setOf(31)).retainsAttachment(owner.session, 31))
    }
    @Test fun changingTopologyRetainsOnlyCommonOwnedAttachmentsAndCreatesNone() {
        val first = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        val owner = first.targets.single()
        val changed = first.copy(signature = "different", targets = listOf(owner))
        val result = GlobalMixerCoverage.retentionOnlyDuringTransition(first, changed)!!
        assertTrue(result.retainsAttachment(owner, 31))
        assertTrue(result.targets.isEmpty())
        val moved = changed.copy(globalOutput = owner.output)
        assertNull(GlobalMixerCoverage.retentionOnlyDuringTransition(first, moved))
        val missing = changed.copy(retainableSessions = emptyList())
        assertFalse(GlobalMixerCoverage.retentionOnlyDuringTransition(first, missing)!!.retainsAttachment(owner, 31))
        val changedId = changed.copy(effects = changed.effects.map { if (it.id == 31) it.copy(id = 32) else it })
        assertFalse(GlobalMixerCoverage.retentionOnlyDuringTransition(first, changedId)!!.retainsAttachment(owner, 31))
        assertNull(GlobalMixerCoverage.retentionOnlyDuringTransition(first, changed.copy(known = false)))
    }
    @Test fun reconciliationKeepsSameOwnedEndpointAcrossPauseAndResume() {
        val foreground = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        val background = inspect(tracks = listOf(Playback(active = false, flags = "600")),
            extra = listOf(effect(31, 361)), owned = setOf(31))
        val owner = GlobalMixerCoverage.OwnedAttachment(foreground.targets.single(), 31, true)
        listOf(foreground, background, foreground).forEach { snapshot ->
            val plan = GlobalMixerCoverage.reconcile(snapshot, listOf(owner))
            assertTrue(plan.obsolete.isEmpty())
            assertTrue(plan.newTargets.isEmpty())
            assertTrue(snapshot.retainsAttachment(owner.session, owner.effectId))
        }
        assertEquals(foreground.signature, background.signature)
    }
    @Test fun reconciliationRemovesInvalidOwnersBeforeConsideringNewTargets() {
        val foreground = inspect(extra = listOf(effect(31, 361)), owned = setOf(31))
        val owner = GlobalMixerCoverage.OwnedAttachment(foreground.targets.single(), 31, true)
        val noControl = GlobalMixerCoverage.reconcile(foreground, listOf(owner.copy(controllable = false)))
        assertEquals(listOf(361), noControl.obsolete)
        val moved = inspect(extra = listOf(effect(31, 361)), owned = setOf(31),
            transformFlinger = { it.replace("I/O handle: 21", "I/O handle: 22") },
            transformPolicy = { it.replace("I/O handle: 21", "I/O handle: 22") })
        val missing = inspect(tracks = emptyList(), extra = listOf(effect(31, 361)), owned = setOf(31))
        val paused = listOf(Playback(active = false))
        val globalOnOwned = GlobalMixerCoverage.inspect(
            output(21, paused, listOf(effect(27, 0, true), effect(31, 361))),
            " Outputs (1):\n" + policyOutput(21, paused) +
                "\n Inputs (0):\nEffect ID: 27; Attached to I/O handle: 21; Session: 0;\n", 27, setOf(31))
        listOf(moved, missing, globalOnOwned, GlobalMixerCoverage.Snapshot(false, "unknown")).forEach {
            assertEquals(listOf(361), GlobalMixerCoverage.reconcile(it, listOf(owner)).obsolete)
        }
        val off = GlobalMixerCoverage.reconcile(foreground, listOf(owner), enabled = false)
        assertEquals(listOf(361), off.obsolete)
        assertTrue(off.newTargets.isEmpty())
    }
    @Test fun pausingOneOwnerDoesNotRecreateOtherActiveOwner() {
        val snapshot = inspect(tracks = listOf(Playback(active = false, flags = "600"),
            Playback(session = 362, port = 71)), extra = listOf(effect(31, 361), effect(32, 362)), owned = setOf(31, 32))
        val owners = snapshot.retainableSessions.map {
            GlobalMixerCoverage.OwnedAttachment(it, if (it.id == 361) 31 else 32, true)
        }
        assertEquals(2, owners.size)
        assertEquals(listOf(362), snapshot.targets.map { it.id })
        val plan = GlobalMixerCoverage.reconcile(snapshot, owners)
        assertTrue(plan.obsolete.isEmpty())
        assertTrue(plan.newTargets.isEmpty())
    }
    @Test fun retainedPausedOwnersConsumeCapacityAndDoNotBlockOtherActiveOwners() {
        val paused = (1..8).map { Playback(session = it, port = it, active = false, flags = "600") }
        val active = Playback(session = 90, port = 90)
        val effects = paused.map { effect(it.session + 100, it.session) }
        val snapshot = inspect(tracks = paused + active, extra = effects, owned = (101..108).toSet())
        val owners = snapshot.retainableSessions.map { GlobalMixerCoverage.OwnedAttachment(it, it.id + 100, true) }
        assertEquals(8, owners.size)
        assertEquals(listOf(90), snapshot.targets.map { it.id })
        val full = GlobalMixerCoverage.reconcile(snapshot, owners)
        assertTrue(full.obsolete.isEmpty())
        assertTrue(full.newTargets.isEmpty())
        val space = GlobalMixerCoverage.reconcile(snapshot, owners.dropLast(1))
        assertEquals(listOf(90), space.newTargets.map { it.id })
        val self = GlobalMixerCoverage.reconcile(snapshot, owners.dropLast(1), excludedUid = active.uid)
        assertTrue(self.newTargets.isEmpty())
    }
    @Test fun exhaustedOrFailedCandidateCannotStarveLaterCandidateOfLastSlot() {
        val paused = (1..7).map { Playback(session = it, port = it, active = false, flags = "600") }
        val candidates = (90..92).map { Playback(session = it, port = it) }
        val snapshot = inspect(tracks = paused + candidates,
            extra = paused.map { effect(it.session + 100, it.session) }, owned = (101..107).toSet())
        val owners = snapshot.retainableSessions.map { GlobalMixerCoverage.OwnedAttachment(it, it.id + 100, true) }
        val plan = GlobalMixerCoverage.reconcile(snapshot, owners)
        assertEquals(listOf(90, 91, 92), plan.newTargets.map { it.id })
        listOf(true, false).forEach { firstExhausted ->
            val budget = SupplementalAttemptBudget()
            val first = plan.newTargets.first()
            if (firstExhausted) repeat(3) { budget.record(first.identity) }
            val live = owners.map { it.session.id }.toMutableSet()
            val attempted = mutableListOf<Int>()
            for (target in plan.newTargets) {
                if (target.id in live || !budget.mayAttempt(target.identity)) continue
                if (live.size >= GlobalMixerCoverage.MAX_SUPPLEMENTAL_SESSIONS) break
                budget.record(target.identity)
                attempted += target.id
                // A either exhausts its budget or fails construction; B succeeds.
                if (target.id != first.id) live += target.id
            }
            assertEquals(if (firstExhausted) listOf(91) else listOf(90, 91), attempted)
            assertEquals(8, live.size)
            assertTrue(91 in live)
            assertFalse(90 in live)
            assertFalse(92 in live)
        }
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
