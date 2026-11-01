package lightning.dsp.interop

/** Attachment evidence only: this does not prove native readiness or audible processing. */
internal object GlobalOutputCoverage {
    enum class State { INCONCLUSIVE, IDLE, COVERED, MISMATCH, MULTIPLE_OUTPUTS, UNSUPPORTED }
    data class Snapshot(
        val state: State,
        val activeOutput: Int? = null,
        val effectOutput: Int? = null,
        val reason: String,
        val signature: String? = null,
    )

    private const val UUID = "f27317f4-c984-4de6-9a90-545759495bf2"
    private const val MAX_DUMP_CHARS = 2_000_000
    private data class Track(val active: Boolean, val uid: Int, val session: Int, val port: Int, val fastOrUnknownType: Boolean)
    private data class Effect(val id: Int, val output: Int, val session: Int, val enabled: Boolean, val suspended: Boolean)
    private data class Output(val id: Int, val type: String, val standby: Boolean, val tracks: List<Track>, val effects: List<Effect>, val fastTracksActive: Boolean)
    private data class PolicyOutput(val id: Int, val active: Boolean, val ports: Set<Int>, val unsupported: Boolean)

    fun inspect(audioFlinger: String, audioPolicy: String): Snapshot = try {
        if (audioFlinger.length > MAX_DUMP_CHARS || audioPolicy.length > MAX_DUMP_CHARS) {
            unknown("Audio diagnostic dump exceeded the supported bound")
        } else inspectParsed(parseOutputs(audioFlinger), parsePolicy(audioPolicy), audioPolicy)
    } catch (_: IllegalArgumentException) {
        unknown("Audio diagnostic layout is incomplete or unsupported")
    }

    private fun inspectParsed(outputs: List<Output>, policy: List<PolicyOutput>, policyText: String): Snapshot {
        require(outputs.isNotEmpty() && policy.isNotEmpty())
        require(outputs.map { it.id }.distinct().size == outputs.size)
        require(policy.map { it.id }.distinct().size == policy.size)
        require(outputs.map { it.id }.toSet() == policy.map { it.id }.toSet())
        val active = outputs.filter { o -> o.tracks.any { it.active } }
        require(active.map { it.id }.toSet() == policy.filter { it.active }.map { it.id }.toSet())
        active.forEach { o ->
            require(!o.standby)
            require(o.tracks.filter { it.active }.map { it.port }.toSet() == policy.single { it.id == o.id }.ports)
        }
        if (active.isEmpty()) return Snapshot(State.IDLE, reason = "No active playback output was observed")
        if (active.size != 1) return Snapshot(State.MULTIPLE_OUTPUTS, reason = "Concurrent playback outputs cannot be covered by a single inferred target")
        val target = active.single()
        if (target.type != "MIXER" || policy.single { it.id == target.id }.unsupported ||
            target.fastTracksActive || target.tracks.any { it.active && it.fastOrUnknownType }) {
            return Snapshot(State.UNSUPPORTED, target.id, reason = "Active playback is FAST, direct, MMAP, offload or another unsupported output type")
        }
        val effects = outputs.flatMap { it.effects }
        if (effects.size != 1) return unknown("A single LightningDSP effect could not be established")
        val effect = effects.single()
        if (effect.session != 0 || !effect.enabled || effect.suspended) return unknown("The LightningDSP effect is not an enabled, unsuspended Global attachment")
        val policyEffect = Regex("Effect ID: ${effect.id}; Attached to I/O handle: (\\d+); Session: (-?\\d+);\\s*[^\\n]*; Enabled; Active")
            .findAll(policyText).toList()
        if (policyEffect.size != 1 || policyEffect.single().groupValues[1].toInt() != effect.output ||
            policyEffect.single().groupValues[2].toInt() != 0) return unknown("Policy and effect attachment evidence disagree")
        val identity = target.tracks.filter { it.active }.map { "${it.uid}:${it.session}:${it.port}" }.sorted().joinToString(",")
        val signature = "${effect.output}->${target.id}:$identity"
        if (effect.output == target.id) return Snapshot(State.COVERED, target.id, effect.output,
            "The active normal mixer output contains the Global effect; processing is not inferred", signature)
        val old = outputs.single { it.id == effect.output }
        if (!old.standby || old.tracks.any { it.active }) return unknown("The existing Global output is not confirmed idle")
        return Snapshot(State.MISMATCH, target.id, effect.output,
            "A single active normal mixer output lacks the Global effect, which remains on a different idle output", signature)
    }

    private fun parseOutputs(text: String): List<Output> {
        // Input/mmap/duplicating threads are not silently treated as ordinary playback mixers.
        val header = Regex("(?m)^(?:Output|Input|Mmap|Duplicating) thread [^\\n]*$")
        val sections = header.findAll(text).toList()
        return sections.mapIndexedNotNull { index, match ->
            if (!match.value.startsWith("Output thread")) return@mapIndexedNotNull null
            val section = text.substring(match.range.first, sections.getOrNull(index + 1)?.range?.first ?: text.length)
                .substringBefore("  Local log:")
            val type = Regex("type \\d+ \\(([^)]+)\\):").find(match.value)?.groupValues?.get(1)
                ?: throw IllegalArgumentException()
            val id = oneInt(section, "(?m)^\\s*I/O handle: (\\d+)\\s*$")
            val standby = Regex("(?m)^\\s*Standby: (yes|no)\\s*$").find(section)?.groupValues?.get(1)
                ?: throw IllegalArgumentException()
            val summary = Regex("(?m)^\\s*(\\d+) Tracks(?: of which (\\d+) are active)?\\s*$").find(section)
                ?: throw IllegalArgumentException()
            val total = summary.groupValues[1].toInt()
            val activeCount = summary.groupValues[2].ifEmpty { require(total == 0); "0" }.toInt()
            val table = section.substring(summary.range.last + 1).substringBefore("Effect Chains")
            val trackPattern = Regex("(?m)^\\s*(?:(\\S+)\\s+)?(\\d+)\\s+(yes|no)\\s+(\\d+)/\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\\S+\\s+0x([0-9a-fA-F]+)\\b[^\\n]*$")
            val tracks = trackPattern.findAll(table).map { row ->
                Track(row.groupValues[3] == "yes", row.groupValues[5].toInt(), row.groupValues[6].toInt(), row.groupValues[7].toInt(), row.groupValues[1].isNotEmpty())
            }.toList()
            require(tracks.size == total && tracks.count { it.active } == activeCount)
            val effectHeaders = Regex("(?m)^\\s*Effect ID (\\d+):\\s*$").findAll(section).toList()
            val effects = effectHeaders.mapIndexedNotNull { effectIndex, effectHeader ->
                val block = section.substring(effectHeader.range.first, effectHeaders.getOrNull(effectIndex + 1)?.range?.first ?: section.length)
                if (!Regex("(?m)^\\s*- UUID: $UUID\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(block)) return@mapIndexedNotNull null
                val state = Regex("Session State Registered Internal Enabled Suspended:\\s*\\n\\s*(\\d+)\\s+\\d+\\s+([yn])\\s+([yn])\\s+([yn])\\s+([yn])").find(block)
                    ?: throw IllegalArgumentException()
                require(state.groupValues[2] == "y")
                Effect(effectHeader.groupValues[1].toInt(), id, state.groupValues[1].toInt(), state.groupValues[4] == "y", state.groupValues[5] == "y")
            }
            val fastMask = Regex("Fast tracks: [^\\n]*activeMask=0x([0-9a-fA-F]+)").find(section)?.groupValues?.get(1)?.toLong(16)
            if (activeCount > 0 && Regex("(?m)^\\s*FastMixer thread ").containsMatchIn(section)) require(fastMask != null)
            Output(id, type, standby == "yes", tracks, effects, fastMask != null && (fastMask and 1L.inv()) != 0L)
        }
    }

    private fun parsePolicy(text: String): List<PolicyOutput> {
        val start = Regex("(?m)^ Outputs \\((\\d+)\\):\\s*$").find(text) ?: throw IllegalArgumentException()
        val section = text.substring(start.range.last + 1).substringBefore("\n Inputs (")
        val headers = Regex("(?m)^\\s*\\d+\\. Port ID: \\d+; I/O handle: (\\d+); IOProfile name:[^\\n]*$").findAll(section).toList()
        require(headers.size == start.groupValues[1].toInt())
        return headers.mapIndexed { index, header ->
            val block = section.substring(header.range.first, headers.getOrNull(index + 1)?.range?.first ?: section.length)
            val count = oneInt(block, "(?m)^\\s*Global active count: (\\d+)\\s*$")
            val ports = Regex("Port ID: (\\d+); Session ID: \\d+; uid \\d+; State: Active\\b")
                .findAll(block).map { it.groupValues[1].toInt() }.toSet()
            require((count > 0) == ports.isNotEmpty())
            PolicyOutput(header.groupValues[1].toInt(), count > 0, ports,
                listOf("AUDIO_OUTPUT_FLAG_RAW", "AUDIO_OUTPUT_FLAG_DIRECT", "AUDIO_OUTPUT_FLAG_MMAP_NOIRQ", "AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD", "AUDIO_OUTPUT_FLAG_BIT_PERFECT").any { it in header.value })
        }
    }

    private fun oneInt(text: String, pattern: String): Int {
        val matches = Regex(pattern).findAll(text).toList()
        require(matches.size == 1)
        return matches.single().groupValues[1].toInt()
    }
    private fun unknown(reason: String) = Snapshot(State.INCONCLUSIVE, reason = reason)
}
