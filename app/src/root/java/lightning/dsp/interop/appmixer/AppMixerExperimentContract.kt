package lightning.dsp.interop.appmixer

/** Explicit, bounded manual experiment. Nothing in the app automatically invokes this entry point. */
internal data class AppMixerExperimentConfig(
    val uids: Set<Int>,
    val seconds: Int,
    val excludeUids: Boolean = false,
    val userId: Int = 0,
    val ownerUid: Int = -1,
    val includeSystemSounds: Boolean = false,
    val localDsp: Boolean = false,
    val ownerExternalFiles: String = "",
) {
    companion object {
        const val MAX_SUPERVISED_UIDS = 1024
        // Media, game, and unknown are always matched; system sounds add notification,
        // notification-event, and sonification usages. One user rule shares the 20-rule cap.
        const val MAX_EXCLUDED_UIDS = 16
        fun maxExcludedUids(includeSystemSounds: Boolean): Int =
            20 - 1 - 3 - if (includeSystemSounds) 3 else 0

        fun parseExcludedUids(argument: String, includeSystemSounds: Boolean = false): Set<Int> {
            require(argument.startsWith("--exclude-uids="))
            val values = argument.removePrefix("--exclude-uids=").split(',').map { it.toInt() }
            require(values.size in 1..maxExcludedUids(includeSystemSounds) && 0 in values && values.all {
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
    /** Non-blocking capture/playback pump. Spare capacity is not a prefill target.
     * Backpressure may occupy at most four quanta; never grow or discard PCM silently. */
    fun runBuffered(
        buffer: ShortArray,
        shouldContinue: () -> Boolean,
        read: (ShortArray) -> Int,
        write: (ShortArray, Int, Int) -> Int,
        idle: () -> Unit,
        nowNanos: () -> Long = System::nanoTime,
        maxWriteStallNanos: Long = 2_000_000_000L,
        onProgress: (Long, Counts, Long) -> Unit = { _, _, _ -> },
        progressIntervalNanos: Long = 5_000_000_000L,
    ): Counts {
        require(buffer.isNotEmpty() && buffer.size <= 65536)
        require(maxWriteStallNanos > 0 && progressIntervalNanos > 0)
        val queue = ShortArray(buffer.size * 4)
        var head = 0
        var queued = 0
        var readSamples = 0L
        var writtenSamples = 0L
        var lastReport = nowNanos()
        var lastRead = lastReport
        var lastWriteProgress = lastReport
        var maxReadGap = 0L
        while (shouldContinue()) {
            var progressed = false
            // Unlike the serial pump, a partial/zero write does not prevent the
            // next capture read while bounded spare capacity remains.
            if (queue.size - queued >= buffer.size) {
                val now = nowNanos()
                maxReadGap = maxOf(maxReadGap, now - lastRead)
                lastRead = now
                val count = read(buffer)
                check(count in 0..buffer.size) { "Mixer capture failed: $count" }
                if (count > 0) {
                    if (queued == 0) lastWriteProgress = now
                    val tail = (head + queued) % queue.size
                    val first = minOf(count, queue.size - tail)
                    buffer.copyInto(queue, tail, 0, first)
                    buffer.copyInto(queue, 0, first, count)
                    queued += count
                    readSamples += count
                    progressed = true
                }
            }
            if (!shouldContinue()) break
            if (queued > 0) {
                val count = minOf(queued, queue.size - head, buffer.size)
                val written = write(queue, head, count)
                check(written in 0..count) { "Mixer playback failed: $written" }
                if (written > 0) {
                    head = (head + written) % queue.size
                    queued -= written
                    writtenSamples += written
                    lastWriteProgress = nowNanos()
                    progressed = true
                } else check(nowNanos() - lastWriteProgress < maxWriteStallNanos) {
                    "Mixer playback stalled"
                }
            }
            val now = nowNanos()
            if (now - lastReport >= progressIntervalNanos) {
                onProgress(now, Counts(readSamples, writtenSamples), maxOf(maxReadGap, now - lastRead))
                lastReport = now
                maxReadGap = 0L
            }
            if (!progressed) idle()
        }
        return Counts(readSamples, writtenSamples)
    }
    data class Counts(val readSamples: Long, val writtenSamples: Long)
    fun run(
        buffer: ShortArray,
        shouldContinue: () -> Boolean,
        read: (ShortArray) -> Int,
        write: (ShortArray, Int, Int) -> Int,
        idle: () -> Unit,
        nowNanos: () -> Long = System::nanoTime,
        maxWriteStallNanos: Long = 2_000_000_000L,
        onProgress: (Long, Counts, Long) -> Unit = { _, _, _ -> },
        progressIntervalNanos: Long = 5_000_000_000L,
    ): Counts {
        require(buffer.isNotEmpty())
        require(maxWriteStallNanos > 0 && progressIntervalNanos > 0)
        var readSamples = 0L
        var writtenSamples = 0L
        var lastReport = nowNanos()
        var lastRead = lastReport
        var maxReadGap = 0L
        fun progress() {
            val now = nowNanos()
            if (now - lastReport >= progressIntervalNanos) {
                onProgress(now, Counts(readSamples, writtenSamples), maxOf(maxReadGap, now - lastRead))
                lastReport = now
                maxReadGap = 0L
            }
        }
        while (shouldContinue()) {
            val readTime = nowNanos()
            maxReadGap = maxOf(maxReadGap, readTime - lastRead)
            lastRead = readTime
            progress()
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
                    progress()
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

/** Keep the relay quantum aligned with the device's output period instead of buffering 40+ ms. */
internal object AppMixerLatencyPolicy {
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val DEFAULT_FRAMES_PER_BURST = 240 // 5 ms at 48 kHz
    const val MIN_FRAMES_PER_BURST = 120
    const val MAX_FRAMES_PER_BURST = 960
    const val TARGET_PLAYBACK_BUFFER_FRAMES = 960 // 20 ms at 48 kHz; platform may clamp upward
    const val MAX_PLAYBACK_BUFFER_FRAMES = 3840 // 80 ms, unless the platform requires more

    fun playbackBufferFrames(capacityFrames: Int, minimumFrames: Int = 1): Int {
        require(capacityFrames > 0 && minimumFrames > 0)
        return minOf(capacityFrames, maxOf(minimumFrames, TARGET_PLAYBACK_BUFFER_FRAMES))
    }

    /** Creation recommendations (especially Bluetooth) must not become the local
     * DSP track's effective queue floor. The platform still clamps each request. */
    fun recoveryFloorFrames(constructionMinimumFrames: Int, localDsp: Boolean): Int {
        require(constructionMinimumFrames > 0)
        return if (localDsp) TARGET_PLAYBACK_BUFFER_FRAMES else constructionMinimumFrames
    }

    /** Reserve bounded headroom, not a growing queue. The effective size starts smaller. */
    fun playbackCapacityBytes(minimumBytes: Int, framesPerBurst: Int): Int =
        maxOf(trackBufferBytes(minimumBytes, framesPerBurst),
            MAX_PLAYBACK_BUFFER_FRAMES * CHANNELS * BYTES_PER_SAMPLE)

    fun framesPerBurst(outputFramesPerBuffer: String?): Int =
        outputFramesPerBuffer?.toIntOrNull()?.coerceIn(MIN_FRAMES_PER_BURST, MAX_FRAMES_PER_BURST)
            ?: DEFAULT_FRAMES_PER_BURST

    fun stereoPcmBytes(frames: Int): Int {
        require(frames in 1..(MAX_FRAMES_PER_BURST * 2))
        return frames * CHANNELS * BYTES_PER_SAMPLE
    }

    fun trackBufferBytes(minimumBytes: Int, framesPerBurst: Int): Int {
        require(minimumBytes > 0)
        // Two periods give AudioTrack a scheduling margin without the old fixed 8 KiB floor.
        return maxOf(minimumBytes, stereoPcmBytes(framesPerBurst * 2))
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

/** Follow shared-memory underrun counters and reapply the bound after a route restores the track. */
internal class AppMixerPlaybackBufferTuner(private val minimumFrames: Int) {
    private var previousUnderruns: Int? = null
    private var lastUnderrunNanos: Long? = null
    // getMinBufferSize sizes construction capacity, not a mandatory effective floor.
    // Let AudioTrack clamp the request; retain the recommendation for underrun recovery.
    private var target = AppMixerLatencyPolicy.TARGET_PLAYBACK_BUFFER_FRAMES

    init { require(minimumFrames > 0) }

    fun targetFrames(capacity: Int, underruns: Int, nowNanos: Long = System.nanoTime()): Int {
        require(capacity > 0 && underruns >= 0)
        val previous = previousUnderruns
        if (previous != null && underruns > previous) {
            target = maxOf(minimumFrames, AppMixerLatencyPolicy.MAX_PLAYBACK_BUFFER_FRAMES)
            lastUnderrunNanos = nowNanos
        } else if (lastUnderrunNanos?.let { nowNanos - it >= 30_000_000_000L } == true) {
            // Once stable, release emergency headroom. Keep the platform's
            // conservative recommendation rather than repeatedly probing the
            // smaller startup queue and causing recurring underruns.
            target = maxOf(minimumFrames, AppMixerLatencyPolicy.TARGET_PLAYBACK_BUFFER_FRAMES)
            lastUnderrunNanos = null
        }
        // A counter reset can occur when AudioTrack restores itself; keep the safe
        // bounded margin but do not mistake the reset for thousands of new xruns.
        previousUnderruns = underruns
        return minOf(capacity, target)
    }
}

internal object AppMixerRelayScheduling {
    fun requestAudioPriority(promote: () -> Unit): Boolean = try {
        promote()
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
