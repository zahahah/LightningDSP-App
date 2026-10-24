package lightning.dsp.interop

/** Machine reply from the generated-NDK root helper, not an audibility claim. */
internal data class NativeControlReply(val ok: Boolean, val fields: Map<String, String>, val raw: String) {
    val ready: Boolean get() = ok && fields["ready"] == "1" &&
        fields["protocol"] == "3" && fields["contexts"] == "1" &&
        fields["writable"] == "1" && fields["publication"] == "3"
    val accepted: Boolean get() = ok && fields["accepted"]?.toLongOrNull()?.let { it > 0 } == true
    val applied: Boolean get() = accepted && fields["pending"] == "0" &&
        fields["applied"] == fields["accepted"]

    companion object {
        fun parse(line: String): NativeControlReply? {
            if (!line.startsWith("LDSPCTL:")) return null
            val parts = line.removePrefix("LDSPCTL:").split(':')
            if (parts.firstOrNull() !in setOf("OK", "ERR")) return null
            val fields = parts.drop(1).mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            return NativeControlReply(parts.first() == "OK", fields, line)
        }
    }
}
