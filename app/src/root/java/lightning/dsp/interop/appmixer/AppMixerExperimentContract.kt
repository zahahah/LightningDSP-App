package lightning.dsp.interop.appmixer

/** Explicit, bounded manual experiment. Nothing in the app automatically invokes this entry point. */
internal data class AppMixerExperimentConfig(val uids: Set<Int>, val seconds: Int, val excludeUids: Boolean = false, val userId: Int = 0, val ownerUid: Int = -1) {
    companion object {
        const val MAX_SUPERVISED_UIDS = 1024
        // Two usages, one user, and 17 UID exclusions fit Android's 20-criterion limit.
        const val MAX_EXCLUDED_UIDS = 17
        fun parseExcludedUids(argument: String): Set<Int> {
            require(argument.startsWith("--exclude-uids="))
            val values = argument.removePrefix("--exclude-uids=").split(',').map { it.toInt() }
            require(values.size in 1..MAX_EXCLUDED_UIDS && 0 in values && values.all {
                it == 0 || (it >= 0 && it % 100000 in 10000..19999)
            }) { "Invalid or excessive UID exclusions" }
            return values.toSet()
        }
        fun parse(args: Array<String>): AppMixerExperimentConfig {
            require(args.size == 4 && args[0] == "--manual-routing-test" &&
                args[3] == "--acknowledge-audio-interruption") { "Explicit manual routing acknowledgement required" }
            require(args[1].startsWith("--uids=") && args[2].startsWith("--seconds="))
            val values = parseUids(args[1], 16)
            val seconds = args[2].removePrefix("--seconds=").toInt()
            require(seconds in 5..90) { "Experiment duration must be between 5 and 90 seconds" }
            return AppMixerExperimentConfig(values, seconds)
        }

        fun parseUids(argument: String, limit: Int): Set<Int> {
            require(argument.startsWith("--uids=") && limit in 1..MAX_SUPERVISED_UIDS)
            val values = argument.removePrefix("--uids=").split(',').map { it.toInt() }
            require(values.size in 1..limit && values.all { it >= 0 && it % 100000 in 10000..19999 }) {
                "Only bounded explicit application UIDs are accepted"
            }
            return values.toSet()
        }
    }
}

/** One fixed-size buffer; partial writes are drained before reading the next block. */
internal object AppMixerPcmRelay {
    data class Counts(val readSamples: Long, val writtenSamples: Long)
    fun run(
        buffer: ShortArray,
        shouldContinue: () -> Boolean,
        read: (ShortArray) -> Int,
        write: (ShortArray, Int, Int) -> Int,
        idle: () -> Unit,
        nowNanos: () -> Long = System::nanoTime,
        maxWriteStallNanos: Long = 2_000_000_000L,
    ): Counts {
        require(buffer.isNotEmpty())
        require(maxWriteStallNanos > 0)
        var readSamples = 0L
        var writtenSamples = 0L
        while (shouldContinue()) {
            val count = read(buffer)
            check(count in 0..buffer.size) { "Mixer capture failed: $count" }
            if (count == 0) { idle(); continue }
            readSamples += count
            var offset = 0
            var lastProgress = nowNanos()
            while (offset < count && shouldContinue()) {
                val written = write(buffer, offset, count - offset)
                check(written in 0..(count - offset)) { "Mixer playback failed: $written" }
                if (written == 0) {
                    check(nowNanos() - lastProgress < maxWriteStallNanos) { "Mixer playback stalled" }
                    idle()
                } else {
                    offset += written
                    writtenSamples += written
                    lastProgress = nowNanos()
                }
            }
        }
        return Counts(readSamples, writtenSamples)
    }
}

/** Bounded pipe framing; EOF, malformed and overlong commands all fail closed. */
internal object AppMixerControlProtocol {
    fun readCommand(input: java.io.Reader, maxLength: Int = 16): String? {
        val command = StringBuilder()
        while (true) {
            val char = input.read()
            if (char < 0) return null
            if (char == '\n'.code) return command.toString()
            if (char == '\r'.code || command.length == maxLength) return null
            command.append(char.toChar())
        }
    }
}
