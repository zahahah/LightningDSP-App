package lightning.dsp.interop

/** Conservative attachment evidence, never proof of frames processed or audible DSP. */
internal object GlobalMixerCoverage {
    const val MAX_DUMP_CHARS = 2_000_000
    const val MAX_SUPPLEMENTAL_SESSIONS = 8
    private const val UUID = "f27317f4-c984-4de6-9a90-545759495bf2"
    private const val PERFORMANCE_FLAGS = 0x10eL // PRIMARY, FAST, DEEP_BUFFER, RAW

    data class Session(val id: Int, val uid: Int, val output: Int, val ports: Set<Int>) {
        val identity: String get() = "$id:$uid:$output:${ports.sorted().joinToString(",")}"
    }
    data class Effect(val id: Int, val session: Int, val output: Int, val enabled: Boolean, val suspended: Boolean)
    data class Snapshot(
        val known: Boolean,
        val reason: String,
        val globalOutput: Int? = null,
        val targets: List<Session> = emptyList(),
        val effects: List<Effect> = emptyList(),
        val activeSessions: Int = 0,
        val signature: String = "",
    ) {
        fun ownsAttachment(session: Session, effectId: Int): Boolean = effects.any {
            it.id == effectId && it.session == session.id && it.output == session.output
        }
    }
    private data class Track(val active: Boolean, val uid: Int, val session: Int, val port: Int,
                             val normal: Boolean, val format: Int, val channels: Long, val stream: Int)
    private data class Output(val id: Int, val type: String, val standby: Boolean,
                              val tracks: List<Track>, val effects: List<Effect>)
    private data class Client(val uid: Int, val session: Int, val port: Int, val compatible: Boolean)
    private data class PolicyOutput(val id: Int, val flags: Long, val clients: List<Client>)

    fun inspect(flinger: String, policy: String, globalEffectId: Int,
                ownedEffectIds: Set<Int> = emptySet()): Snapshot {
        if (flinger.length > MAX_DUMP_CHARS || policy.length > MAX_DUMP_CHARS)
            return Snapshot(false, "Audio diagnostic size limit exceeded")
        if (globalEffectId <= 0) return Snapshot(false, "Invalid global effect identity")
        var reason = "AudioFlinger diagnostic layout is incomplete or unsupported"
        return try {
            val outputs = parseOutputs(flinger)
            require(outputs.isNotEmpty())
            reason = "AudioPolicy diagnostic layout is incomplete or unsupported"
            val policyOutputs = parsePolicy(policy)
            require(policyOutputs.isNotEmpty())
            reason = "Audio topology or baseline attachment identity is inconsistent or missing"
            inspectParsed(outputs, policyOutputs, policy, globalEffectId, ownedEffectIds)
        } catch (_: IllegalArgumentException) {
            Snapshot(false, reason)
        } catch (_: NoSuchElementException) {
            Snapshot(false, reason)
        }
    }

    private fun inspectParsed(outputs: List<Output>, policy: List<PolicyOutput>, policyText: String,
                              globalEffectId: Int, owned: Set<Int>): Snapshot {
        require(outputs.isNotEmpty() && policy.isNotEmpty())
        require(outputs.size <= 64 && policy.size <= 64)
        require(outputs.map { it.id }.distinct().size == outputs.size)
        require(policy.map { it.id }.distinct().size == policy.size)
        val active = outputs.filter { o -> o.tracks.any { it.active } }
        require(active.map { it.id }.toSet() == policy.filter { it.clients.isNotEmpty() }.map { it.id }.toSet())
        active.forEach { out ->
            require(!out.standby)
            val tracks = out.tracks.filter { it.active }
            val clients = policy.single { it.id == out.id }.clients
            require(tracks.map { Triple(it.uid, it.session, it.port) }.toSet() ==
                    clients.map { Triple(it.uid, it.session, it.port) }.toSet())
        }
        val effects = outputs.flatMap { it.effects }
        require(effects.map { it.id }.distinct().size == effects.size)
        val global = effects.single { it.id == globalEffectId && it.session == 0 }
        val policyGlobal = Regex("Effect ID: $globalEffectId; Attached to I/O handle: (\\d+); Session: (-?\\d+);")
            .findAll(policyText).toList()
        require(policyGlobal.size == 1 && policyGlobal.single().groupValues[1].toInt() == global.output &&
                policyGlobal.single().groupValues[2].toInt() == 0)
        // A suspended baseline still reserves its mixer. Never force it back or build
        // a feedback loop of new session effects on the same mixer.
        val reserved = effects.filter { it.session <= 0 }.map { it.output }.toSet()
        val allActive = active.flatMap { out -> out.tracks.filter { it.active }.map { out.id to it } }
        val grouped = allActive.groupBy { it.second.session }
        val excluded = linkedMapOf<String, Int>()
        fun exclude(reason: String): Session? {
            excluded[reason] = excluded.getOrDefault(reason, 0) + 1
            return null
        }
        val targets = grouped.mapNotNull { (sid, rows) ->
            val outputIds = rows.map { it.first }.toSet()
            val uids = rows.map { it.second.uid }.toSet()
            if (sid <= 0 || uids.size != 1 || uids.single() < 10000 || outputIds.size != 1)
                return@mapNotNull exclude("session identity guards")
            val outputId = outputIds.single()
            val output = outputs.single { it.id == outputId }
            val descriptor = policy.single { it.id == outputId }
            if (outputId in reserved) return@mapNotNull exclude("reserved output guards")
            if (output.type != "MIXER" || (descriptor.flags and PERFORMANCE_FLAGS.inv()) != 0L)
                return@mapNotNull exclude("output type/flags guards")
            if (rows.any { (_, t) -> !t.normal || t.channels != 3L || t.stream != 3 || t.format !in setOf(1, 3, 4, 5, 6) })
                return@mapNotNull exclude("track type/flags/layout/format guards")
            if (descriptor.clients.filter { it.session == sid }.any { !it.compatible })
                return@mapNotNull exclude("policy compatibility guards")
            if (effects.any { it.session == sid && it.id !in owned }) return@mapNotNull exclude("existing effect guards")
            Session(sid, uids.single(), outputId, rows.map { it.second.port }.toSet())
        }.sortedBy { it.id }.take(MAX_SUPPLEMENTAL_SESSIONS)
        val signature = "${global.id}:${global.output}:" + allActive.map { (out, t) ->
            "$out:${t.uid}:${t.session}:${t.port}:${t.normal}:${t.format}:${t.channels}:${t.stream}"
        }.sorted().joinToString("|") + ":targets=" + targets.joinToString("|") { it.identity }
        val reason = "Observed attachment topology; processing is not inferred" + if (targets.isEmpty()) {
            "; no supplemental target: " + if (grouped.isEmpty()) "no active sessions"
            else excluded.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else ""
        return Snapshot(true, reason, global.output,
            targets, effects, grouped.size, signature)
    }

    private fun parseOutputs(text: String): List<Output> {
        val headers = Regex("(?m)^(?:Output|Input|Mmap|Duplicating) thread [^\\n]*$").findAll(text).toList()
        return headers.mapIndexedNotNull { index, header ->
            if (!header.value.startsWith("Output thread")) return@mapIndexedNotNull null
            val block = text.substring(header.range.first, headers.getOrNull(index + 1)?.range?.first ?: text.length)
                .substringBefore("  Local log:")
            val type = Regex("type \\d+ \\(([^)]+)\\):").find(header.value)?.groupValues?.get(1)
                ?: throw IllegalArgumentException()
            val id = oneInt(block, "(?m)^\\s*I/O handle: (\\d+)\\s*$")
            val standby = Regex("(?m)^\\s*Standby: (yes|no)\\s*$").find(block)?.groupValues?.get(1)
                ?: throw IllegalArgumentException()
            val summary = Regex("(?m)^\\s*(\\d+) Tracks(?: of which (\\d+) are active)?\\s*$").find(block)
                ?: throw IllegalArgumentException()
            val total = summary.groupValues[1].toInt()
            require(total <= 256)
            val active = summary.groupValues[2].ifEmpty { require(total == 0); "0" }.toInt()
            val table = block.substring(summary.range.last + 1).substringBefore("Effect Chains")
            val normalRow = normalTrackLayout(table)
            val row = Regex("(?m)^\\s*(?:(\\S+)\\s+)?(\\d+)\\s+(yes|no)\\s+(\\d+)/\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\\S+\\s+0x[0-9a-fA-F]+\\s+([0-9a-fA-F]+)\\s+([0-9a-fA-F]+)\\s+\\d+\\s+(\\d+)\\b[^\\n]*$")
            val tracks = row.findAll(table).map {
                Track(it.groupValues[3] == "yes", it.groupValues[5].toInt(), it.groupValues[6].toInt(),
                    it.groupValues[7].toInt(), it.groupValues[1].isEmpty() && normalRow?.matches(it.value) == true,
                    it.groupValues[8].toInt(16),
                    it.groupValues[9].toLong(16), it.groupValues[10].toInt())
            }.toList()
            require(tracks.size == total && tracks.count { it.active } == active)
            require(tracks.filter { it.active }.map { it.port }.distinct().size == active)
            val effectHeaders = Regex("(?m)^\\s*Effect ID (\\d+):\\s*$").findAll(block).toList()
            require(effectHeaders.size <= 256)
            val chains = oneInt(block, "(?m)^\\s*(\\d+) Effect Chains\\s*$")
            val chainSizes = Regex("(?m)^\\s*(\\d+) effects for session -?\\d+\\s*$")
                .findAll(block).map { it.groupValues[1].toInt() }.toList()
            require(chainSizes.size == chains && chainSizes.sum() == effectHeaders.size)
            val effects = effectHeaders.mapIndexedNotNull { i, effectHeader ->
                val effect = block.substring(effectHeader.range.first, effectHeaders.getOrNull(i + 1)?.range?.first ?: block.length)
                if (!Regex("(?m)^\\s*- UUID: $UUID\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(effect)) return@mapIndexedNotNull null
                val state = Regex("Session State Registered Internal Enabled Suspended:\\s*\\n\\s*(-?\\d+)\\s+\\d+\\s+([yn])\\s+([yn])\\s+([yn])\\s+([yn])").find(effect)
                    ?: throw IllegalArgumentException()
                require(state.groupValues[2] == "y")
                Effect(effectHeader.groupValues[1].toInt(), state.groupValues[1].toInt(), id,
                    state.groupValues[4] == "y", state.groupValues[5] == "y")
            }
            Output(id, type, standby == "yes", tracks, effects)
        }
    }

    private fun normalTrackLayout(table: String): Regex? {
        val lines = table.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }.toList()
        val headers = lines.filter { it.startsWith("Type Id Active ") }
        if (headers.size != 1) return null
        val prefix = "Type Id Active Client(pid/uid) Session Port Id S Flags Format Chn mask SRate ST Usg CT G db L dB R dB VS dB PortVol dB PortMuted Server FrmCnt FrmRdy F Underruns Flushed "
        val bitPerfect = when (headers.single()) {
            prefix + "BitPerfect InternalMute Latency" -> true
            prefix + "InternalMute Latency" -> false
            else -> return null
        }
        val extended = lines.getOrNull(lines.indexOf(headers.single()) + 1) == "Spat Meta Sce Silent Top"
        val number = "(?:-?\\d+(?:\\.\\d+)?|-inf)"
        // Anchor at the actual Flags column, then consume each known column. Latency
        // may carry a separate status token; Android's spatial counters follow it.
        // Never search for a convenient pair of booleans elsewhere in the row.
        val layout = "^\\s*\\d+\\s+(?:yes|no)\\s+\\d+/\\s*\\d+\\s+\\d+\\s+\\d+\\s+\\S+" +
            "\\s+0x0+\\s+[0-9a-fA-F]+\\s+[0-9a-fA-F]+\\s+\\d+\\s+\\d+" +
            "\\s+[0-9a-fA-F]+\\s+\\d+" + "\\s+$number".repeat(5) +
            "\\s+(?:true|false)\\s+[0-9a-fA-F]+\\s+\\d+\\s+\\d+\\s+[A-Za-z]\\s+\\d+\\s+\\d+" +
            (if (bitPerfect) "\\s+false" else "") + "\\s+(?:true|false)" +
            "\\s+(?:\\d+(?:\\.\\d+)?(?:\\s+[A-Za-z])?|new)" +
            (if (extended) "(?:\\s+\\d+){5}" else "") + "\\s*$"
        return Regex(layout)
    }

    private fun parsePolicy(text: String): List<PolicyOutput> {
        val start = Regex("(?m)^ Outputs \\((\\d+)\\):\\s*$").find(text) ?: throw IllegalArgumentException()
        val section = text.substring(start.range.last + 1).substringBefore("\n Inputs (")
        val headers = Regex("(?m)^\\s*\\d+\\. Port ID: \\d+; I/O handle: (\\d+); IOProfile name:[^\\n]*$").findAll(section).toList()
        require(headers.size == start.groupValues[1].toInt())
        return headers.mapIndexed { index, header ->
            val block = section.substring(header.range.first, headers.getOrNull(index + 1)?.range?.first ?: section.length)
            val flags = Regex("; 0x([0-9a-fA-F]+) \\(").find(header.value)?.groupValues?.get(1)?.toLong(16)
                ?: throw IllegalArgumentException()
            val count = oneInt(block, "(?m)^\\s*Global active count: (\\d+)\\s*$")
            val clientsBlock = block.substringBefore("- AudioTrack active")
            val clientHeaders = Regex("(?m)^\\s*\\d+\\. Port ID: (\\d+); Session ID: (\\d+); uid (\\d+); State: ([^\\s;]+)[^\\n]*$").findAll(clientsBlock).toList()
            val clients = clientHeaders.mapIndexedNotNull { i, h ->
                if (h.groupValues[4] != "Active") return@mapIndexedNotNull null
                val client = clientsBlock.substring(h.range.first, clientHeaders.getOrNull(i + 1)?.range?.first ?: clientsBlock.length)
                val format = Regex("AUDIO_FORMAT_(PCM_[A-Z0-9_]+); \\d+; Channel mask: 0x([0-9a-fA-F]+)").find(client)
                    ?: throw IllegalArgumentException()
                val stream = Regex("Stream: (\\d+); Flags: (?:0x)?([0-9a-fA-F]+);").find(client)
                    ?: throw IllegalArgumentException()
                val usage = Regex("Usage: (AUDIO_USAGE_[A-Z0-9_]+)\\b").find(client)?.groupValues?.get(1)
                    ?: throw IllegalArgumentException()
                val compatible = format.groupValues[1] in setOf("PCM_16_BIT", "PCM_32_BIT", "PCM_8_24_BIT", "PCM_FLOAT", "PCM_24_BIT_PACKED") &&
                    format.groupValues[2].toLong(16) == 3L && stream.groupValues[1] == "3" &&
                    (stream.groupValues[2].toLong(16) and PERFORMANCE_FLAGS.inv()) == 0L &&
                    usage in setOf("AUDIO_USAGE_MEDIA", "AUDIO_USAGE_GAME")
                Client(h.groupValues[3].toInt(), h.groupValues[2].toInt(), h.groupValues[1].toInt(), compatible)
            }
            require((count > 0) == clients.isNotEmpty())
            require(clients.map { it.port }.distinct().size == clients.size)
            PolicyOutput(header.groupValues[1].toInt(), flags, clients)
        }
    }

    private fun oneInt(text: String, pattern: String): Int {
        val matches = Regex(pattern).findAll(text).toList()
        require(matches.size == 1)
        return matches.single().groupValues[1].toInt()
    }
}

/** Retry budget follows actual playback identity, not internal DSP generation churn. */
internal class SupplementalAttemptBudget(private val limit: Int = 3) {
    private val attempts = mutableMapOf<String, Int>()
    fun mayAttempt(identity: String) = attempts.getOrDefault(identity, 0) < limit
    fun record(identity: String) { attempts[identity] = attempts.getOrDefault(identity, 0) + 1 }
    fun retain(identities: Set<String>) { attempts.keys.retainAll(identities) }
    fun clear() = attempts.clear()
}
