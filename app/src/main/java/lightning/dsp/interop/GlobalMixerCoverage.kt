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
        val retainableSessions: List<Session> = emptyList(),
    ) {
        fun retainsAttachment(session: Session, effectId: Int): Boolean =
            known && session in retainableSessions && ownsAttachment(session, effectId)

        fun ownsAttachment(session: Session, effectId: Int): Boolean = effects.any {
            it.id == effectId && it.session == session.id && it.output == session.output
        }
    }
    data class OwnedAttachment(val session: Session, val effectId: Int, val controllable: Boolean)
    data class Reconciliation(val obsolete: List<Int>, val newTargets: List<Session>)

    /** Pure decision shared with the controller: retain validated owners before filling free slots. */
    fun reconcile(snapshot: Snapshot, owned: List<OwnedAttachment>, enabled: Boolean = true,
                  excludedUid: Int? = null): Reconciliation {
        val retained = owned.filter { enabled && it.controllable &&
            snapshot.retainsAttachment(it.session, it.effectId) }
        val retainedIds = retained.map { it.session.id }.toSet()
        val obsolete = owned.filter { it.session.id !in retainedIds }.map { it.session.id }
        // Do not truncate before the controller checks retry eligibility: an exhausted
        // first candidate must not starve later candidates of a free live slot.
        val targets = if (enabled && snapshot.known && retained.size < MAX_SUPPLEMENTAL_SESSIONS)
            snapshot.targets.filter { it.id !in retainedIds && it.uid != excludedUid } else emptyList()
        return Reconciliation(obsolete, targets)
    }
    private data class Track(val active: Boolean, val uid: Int, val session: Int, val port: Int,
                             val normal: Boolean, val format: Int, val channels: Long, val stream: Int,
                             val controlFlags: Long, val paused: Boolean)
    private data class Output(val id: Int, val type: String, val standby: Boolean,
                              val tracks: List<Track>, val effects: List<Effect>)
    private data class Client(val uid: Int, val session: Int, val port: Int, val active: Boolean, val compatible: Boolean)
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
        val effects = outputs.flatMap { it.effects }
        require(effects.map { it.id }.distinct().size == effects.size)
        val ownedPlacements = effects.filter { it.id in owned && it.session > 0 }
            .map { it.session to it.output }.toSet()
        val ownedSessions = ownedPlacements.map { it.first }.toSet()
        val active = outputs.filter { o -> o.tracks.any { it.active } }
        // Activity may change between sequential dumps without changing ownership or
        // topology. Only already-owned sessions may use this retention-only exception.
        outputs.forEach { out ->
            val tracks = out.tracks.filter { it.active && (it.session to out.id) !in ownedPlacements }
            val clients = policy.single { it.id == out.id }.clients.filter { it.active && (it.session to out.id) !in ownedPlacements }
            require(tracks.map { Triple(it.uid, it.session, it.port) }.toSet() ==
                    clients.map { Triple(it.uid, it.session, it.port) }.toSet())
            if (out.tracks.any { it.active }) require(!out.standby)
        }
        require(policy.filter { descriptor -> descriptor.clients.any { it.active && (it.session to descriptor.id) !in ownedPlacements } }
            .all { descriptor -> outputs.any { it.id == descriptor.id } })
        val global = effects.single { it.id == globalEffectId && it.session == 0 }
        val policyGlobal = Regex("Effect ID: $globalEffectId; Attached to I/O handle: (\\d+); Session: (-?\\d+);")
            .findAll(policyText).toList()
        require(policyGlobal.size == 1 && policyGlobal.single().groupValues[1].toInt() == global.output &&
                policyGlobal.single().groupValues[2].toInt() == 0)
        // A suspended baseline still reserves its mixer. Never force it back or build
        // a feedback loop of new session effects on the same mixer.
        val reserved = effects.filter { it.session <= 0 }.map { it.output }.toSet()
        val observed = outputs.flatMap { out -> out.tracks.map { out.id to it } }
        val allActive = observed.filter { it.second.active }
        val grouped = observed.groupBy { it.second.session }
        val excluded = linkedMapOf<String, Int>()
        fun exclude(reason: String): Session? {
            excluded[reason] = excluded.getOrDefault(reason, 0) + 1
            return null
        }
        val eligible = grouped.mapNotNull { (sid, rows) ->
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
            val clients = descriptor.clients.filter { it.session == sid }
            if (rows.map { Triple(it.second.uid, it.second.session, it.second.port) }.toSet() !=
                clients.map { Triple(it.uid, it.session, it.port) }.toSet())
                return@mapNotNull exclude("session identity guards")
            if (clients.any { !it.compatible })
                return@mapNotNull exclude("policy compatibility guards")
            // Flags here are audio_track_cblk_t flags, not output capabilities.
            // Only retain a proven owned paused session for INTERRUPT/STREAM_END_DONE;
            // invalid/disabled/poisoned and unknown flags remain ineligible.
            if (rows.any { (_, t) -> t.controlFlags != 0L &&
                !(sid in ownedSessions && !t.active && t.paused &&
                    (t.controlFlags and 0x600L.inv()) == 0L &&
                    clients.single { it.port == t.port }.active == false) })
                return@mapNotNull exclude("track control-block guards")
            if (effects.any { it.session == sid && it.id !in owned }) return@mapNotNull exclude("existing effect guards")
            Session(sid, uids.single(), outputId, rows.map { it.second.port }.toSet())
        }.sortedBy { it.id }
        val activeIds = grouped.filter { (_, rows) -> rows.any { it.second.active } &&
            rows.all { (out, track) -> policy.single { it.id == out }.clients.any {
                it.session == track.session && it.port == track.port && it.active == track.active
            } }
        }.keys
        val targets = eligible.filter { it.id in activeIds }.take(MAX_SUPPLEMENTAL_SESSIONS)
        val retained = eligible.filter { it.id in ownedSessions }
        val signature = "${global.id}:${global.output}:" + observed.filter {
            it.second.active || it.second.session in ownedSessions
        }.map { (out, t) ->
            "$out:${t.uid}:${t.session}:${t.port}:${t.normal}:${t.format}:${t.channels}:${t.stream}:" +
                if (t.session in ownedSessions) "owned" else t.active
        }.sorted().joinToString("|") + ":outputs=" + outputs.sortedBy { it.id }.joinToString("|") {
            "${it.id}:${it.type}:${policy.single { p -> p.id == it.id }.flags}"
        } + ":new=" + targets.filter { it.id !in ownedSessions }.joinToString("|") { it.identity } +
            ":retained=" + retained.joinToString("|") { it.identity }
        val reason = "Observed attachment topology; processing is not inferred" + if (targets.isEmpty()) {
            "; no supplemental target: " + if (allActive.isEmpty()) "no active sessions"
            else excluded.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else ""
        return Snapshot(true, reason, global.output,
            targets, effects, allActive.map { it.second.session }.distinct().size, signature, retained)
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
                    it.groupValues[9].toLong(16), it.groupValues[10].toInt(),
                    Regex("\\s([A-Za-z])\\s+0x([0-9a-fA-F]+)\\s").find(it.value)!!.groupValues[2].toLong(16),
                    Regex("\\sP\\s+0x").containsMatchIn(it.value))
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
            "\\s+0x[0-9a-fA-F]+\\s+[0-9a-fA-F]+\\s+[0-9a-fA-F]+\\s+\\d+\\s+\\d+" +
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
                require(h.groupValues[4] in setOf("Active", "Inactive"))
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
                Client(h.groupValues[3].toInt(), h.groupValues[2].toInt(), h.groupValues[1].toInt(),
                    h.groupValues[4] == "Active", compatible)
            }
            require((count > 0) == clients.any { it.active })
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
