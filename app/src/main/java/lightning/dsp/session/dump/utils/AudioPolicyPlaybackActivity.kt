package lightning.dsp.session.dump.utils

/** Only the complete, explicitly understood output-client layout can prove inactivity. */
internal object AudioPolicyPlaybackActivity {
    data class Identity(val sessionId: Int, val uid: Int)

    fun parse(dump: String): Map<Identity, Boolean?> {
        val outputs = Regex("(?m)^ Outputs \\((\\d+)\\):[ \\t]*$").findAll(dump).toList()
        if (outputs.size != 1) return emptyMap()
        val start = outputs.single()
        val inputs = Regex("(?m)^ Inputs \\((\\d+)\\):[ \\t]*$").find(dump, start.range.last + 1)
            ?: return emptyMap()
        val text = dump.substring(start.range.last + 1, inputs.range.first)
        val headers = Regex("(?m)^  \\d+\\. Port ID: \\d+; I/O handle: (\\d+);[^\\n]*$").findAll(text).toList()
        val count = start.groupValues[1].toIntOrNull() ?: return emptyMap()
        if (count !in 0..64 || headers.size != count || headers.map { it.groupValues[1] }.distinct().size != count) return emptyMap()
        val observations = mutableListOf<Pair<Identity, Boolean?>>()
        for ((index, header) in headers.withIndex()) {
            val block = text.substring(header.range.first, headers.getOrNull(index + 1)?.range?.first ?: text.length)
            val activeCount = Regex("(?m)^ +Global active count: (\\d+)[ \\t]*$").find(block)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return emptyMap()
            val lists = Regex("(?m)^ +- AudioTrack (clients|active \\(stream\\) clients) \\((\\d+)\\):[ \\t]*$")
                .findAll(block).toList()
            if (lists.isEmpty()) {
                if (activeCount != 0 || block.contains("Session ID:") || block.contains("Session Id:")) return emptyMap()
                continue
            }
            if (lists.count { it.groupValues[1] == "clients" } != 1) return emptyMap()
            val primary = mutableSetOf<Pair<Identity, Int>>()
            val primaryPorts = mutableSetOf<Int>()
            val blockObservations = mutableListOf<Pair<Identity, Boolean?>>()
            for ((listIndex, list) in lists.withIndex()) {
                val body = block.substring(list.range.last + 1, lists.getOrNull(listIndex + 1)?.range?.first ?: block.length)
                val rows = Regex("(?m)^ +(?:id )?\\d+[.:] Port ID: (\\d+); Session ID: (\\d+); uid (\\d+); State: ([^\\n]+)$")
                    .findAll(body).toList()
                val expected = list.groupValues[2].toIntOrNull() ?: return emptyMap()
                if (expected !in 0..4096 || rows.size != expected) return emptyMap()
                // A malformed/unrecognized client row must not silently disappear.
                if (Regex("(?m)^.*Session I[Dd]:.*$").findAll(body).count() != rows.size) return emptyMap()
                val activePorts = mutableSetOf<Int>()
                for (row in rows) {
                    val port = row.groupValues[1].toIntOrNull() ?: return emptyMap()
                    val sid = row.groupValues[2].toIntOrNull() ?: return emptyMap()
                    val uid = row.groupValues[3].toIntOrNull() ?: return emptyMap()
                    val identity = Identity(sid, uid)
                    if (list.groupValues[1] == "clients") {
                        if (!primaryPorts.add(port)) return emptyMap()
                        primary += identity to port
                    } else if ((identity to port) !in primary || !activePorts.add(port)) return emptyMap()
                    val active = when (row.groupValues[4].trim()) { "Active" -> true; "Inactive" -> false; else -> null }
                    blockObservations += identity to active
                }
            }
            if (activeCount > 0 && blockObservations.none { it.second == true }) return emptyMap()
            observations += blockObservations
        }
        val conflictingSessions = observations.groupBy { it.first.sessionId }
            .filterValues { rows -> rows.map { it.first.uid }.distinct().size > 1 }.keys
        return observations.groupBy({ it.first }, { it.second }).mapValues { (identity, states) ->
            when {
                identity.sessionId in conflictingSessions -> null
                states.any { it == true } -> true
                states.any { it == null } -> null
                else -> false
            }
        }
    }
}
