package lightning.dsp.session.dump.utils

/** Current player configuration rows only; historical player-event logs are not observations. */
internal object AudioServicePlaybackActivity {
    data class Configuration(
        val uid: Int,
        val pid: Int,
        val usage: String,
        val content: String,
        val sessionId: Int?,
        val active: Boolean?,
    )

    private val currentRow = Regex("^(?:AudioPlaybackConfiguration\\b|ID:\\d+\\b)")
    private val ownerField = Regex("\\bu/pid:(\\d+)/(\\d+)\\b")
    private val usageField = Regex("\\busage=(\\w+)")
    private val contentField = Regex("\\bcontent=(\\w+)")
    private val sessionField = Regex("\\bsessionId:(\\d+)\\b")
    private val stateField = Regex("\\bstate:([\\w-]+)")

    /** AOSP closes the current players section with the ducked-player section.
     * A missing/truncated/unsupported section must not look like an empty model.
     */
    fun hasCompleteEmptyPlayers(dump: String): Boolean {
        val lines = dump.lines().map(String::trim)
        val monitors = lines.indices.filter { lines[it].startsWith("PlaybackActivityMonitor dump time: ") }
        val players = lines.indices.filter { lines[it] == "players:" }
        if (monitors.size != 1 || players.size != 1 || players.single() <= monitors.single()) return false
        val start = players.single()
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it] == "ducked players piids:" || lines[it] == "ducked player piids:"
        } ?: return false
        return lines.subList(start + 1, end).all(String::isEmpty)
    }

    fun parse(dump: String): List<Configuration> = dump.lineSequence().mapNotNull { line ->
        val row = line.trimStart()
        if (!currentRow.containsMatchIn(row)) return@mapNotNull null
        val owner = ownerField.find(row) ?: return@mapNotNull null
        val uid = owner.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val pid = owner.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val usage = usageField.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
        val content = contentField.find(row)?.groupValues?.get(1) ?: "CONTENT_TYPE_UNKNOWN"
        val sid = sessionField.find(row)?.groupValues?.get(1)?.toIntOrNull()
        val states = stateField.findAll(row).map { it.groupValues[1] }.toList()
        val active = when (states.singleOrNull()) {
            "started" -> true
            "paused", "stopped", "released", "idle" -> false
            else -> null
        }
        Configuration(uid, pid, usage, content, sid, active)
    }.toList()

    /** A shared session is inactive only if every current player is explicitly inactive. */
    fun merge(first: Boolean?, second: Boolean?): Boolean? = when {
        first == true || second == true -> true
        first == null || second == null -> null
        else -> false
    }
}
