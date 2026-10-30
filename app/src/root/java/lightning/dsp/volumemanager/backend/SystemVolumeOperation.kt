package lightning.dsp.volumemanager.backend

import android.media.AudioManager
import android.app.NotificationManager
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** A bounded, one-shot operation on a real Android system volume stream. */
internal data class SystemVolumeOperation(
    val id: Long,
    val kind: Kind,
    val stream: Int,
    val value: Int,
) {
    enum class Kind { SET, ADJUST, RINGER, INTERRUPTION_FILTER }

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("volume_operation", org.json.JSONObject()
            .put("id", id)
            .put("kind", kind.name.lowercase())
            .put("stream", stream)
            .put("value", value))

    companion object {
        private const val MAX_STREAM_INDEX = 1_000

        fun set(id: Long, stream: Int, index: Int, minimum: Int, maximum: Int): SystemVolumeOperation {
            require(id > 0)
            require(isSupportedStream(stream))
            require(minimum >= 0 && maximum >= minimum && maximum <= MAX_STREAM_INDEX)
            require(index in minimum..maximum)
            return SystemVolumeOperation(id, Kind.SET, stream, index)
        }

        fun adjust(id: Long, direction: Int, stream: Int = AudioManager.USE_DEFAULT_STREAM_TYPE): SystemVolumeOperation {
            require(id > 0)
            require(direction == AudioManager.ADJUST_RAISE || direction == AudioManager.ADJUST_LOWER)
            require(stream == AudioManager.USE_DEFAULT_STREAM_TYPE || isSupportedStream(stream))
            return SystemVolumeOperation(id, Kind.ADJUST, stream, direction)
        }

        fun ringer(id: Long, mode: Int): SystemVolumeOperation {
            require(id > 0)
            require(mode == AudioManager.RINGER_MODE_NORMAL || mode == AudioManager.RINGER_MODE_VIBRATE ||
                mode == AudioManager.RINGER_MODE_SILENT)
            return SystemVolumeOperation(id, Kind.RINGER, AudioManager.USE_DEFAULT_STREAM_TYPE, mode)
        }

        fun interruptionFilter(id: Long, filter: Int): SystemVolumeOperation {
            require(id > 0)
            require(filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            return SystemVolumeOperation(id, Kind.INTERRUPTION_FILTER, AudioManager.USE_DEFAULT_STREAM_TYPE, filter)
        }

        fun parse(json: org.json.JSONObject): SystemVolumeOperation? = runCatching {
            val payload = json.getJSONObject("volume_operation")
            val id = payload.getLong("id")
            val stream = payload.getInt("stream")
            val value = payload.getInt("value")
            when (payload.getString("kind")) {
                "set" -> {
                    require(value in 0..MAX_STREAM_INDEX)
                    require(isSupportedStream(stream))
                    require(id > 0)
                    SystemVolumeOperation(id, Kind.SET, stream, value)
                }
                "adjust" -> adjust(id, value, stream)
                "ringer" -> { require(stream == AudioManager.USE_DEFAULT_STREAM_TYPE); ringer(id, value) }
                "interruption_filter" -> { require(stream == AudioManager.USE_DEFAULT_STREAM_TYPE); interruptionFilter(id, value) }
                else -> null
            }
        }.getOrNull()

        fun isSupportedStream(stream: Int): Boolean = stream == AudioManager.STREAM_MUSIC ||
            stream == AudioManager.STREAM_VOICE_CALL || stream == AudioManager.STREAM_RING ||
            stream == AudioManager.STREAM_ALARM || stream == AudioManager.STREAM_NOTIFICATION
    }
}

/** Latest absolute target per stream, FIFO for non-coalescible volume-key increments. */
internal class SystemVolumeOperationQueue(private val capacity: Int = 32) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, SystemVolumeOperation>()

    init { require(capacity > 0) }

    fun offer(operation: SystemVolumeOperation): Boolean = synchronized(lock) {
        val key = when (operation.kind) {
            SystemVolumeOperation.Kind.SET -> "set:${operation.stream}"
            SystemVolumeOperation.Kind.ADJUST -> "adjust:${operation.id}"
            SystemVolumeOperation.Kind.RINGER -> "ringer"
            SystemVolumeOperation.Kind.INTERRUPTION_FILTER -> "interruption_filter"
        }
        pending.remove(key)
        if (pending.size >= capacity) return@synchronized false
        pending[key] = operation
        true
    }

    fun poll(): SystemVolumeOperation? = synchronized(lock) {
        val iterator = pending.entries.iterator()
        if (!iterator.hasNext()) return@synchronized null
        val next = iterator.next().value
        iterator.remove()
        next
    }

    fun clear() = synchronized(lock) { pending.clear() }

    val size: Int get() = synchronized(lock) { pending.size }
}

/** Drops duplicate operation IDs without conflating independent streams or later sessions. */
internal class SystemVolumeOperationReplayGuard(private val capacity: Int = 128) {
    private val seen = LinkedHashSet<Long>()

    init { require(capacity > 0) }

    @Synchronized
    fun accept(id: Long): Boolean {
        if (id <= 0 || !seen.add(id)) return false
        while (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
