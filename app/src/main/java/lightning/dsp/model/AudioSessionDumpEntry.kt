package lightning.dsp.model

data class AudioSessionDumpEntry(val uid: Int, val packageName: String, val usage: String, val content: String, val playbackActive: Boolean? = null)
{
    override fun toString(): String {
        return "uid=$uid; package=$packageName; usage=$usage; content=$content; playbackActive=$playbackActive; isUsageRecordable=${isUsageRecordable(usage)}"
    }

    fun isUsageRecordable(includeSystemSounds: Boolean = false): Boolean
    {
        return isUsageRecordable(usage, includeSystemSounds)
    }

    companion object
    {
        private const val USAGE_UNKNOWN = "USAGE_UNKNOWN"
        private const val USAGE_MEDIA = "USAGE_MEDIA"
        private const val USAGE_GAME = "USAGE_GAME"
        private const val USAGE_NOTIFICATION = "USAGE_NOTIFICATION"
        private const val USAGE_NOTIFICATION_EVENT = "USAGE_NOTIFICATION_EVENT"
        private const val USAGE_ASSISTANCE_SONIFICATION = "USAGE_ASSISTANCE_SONIFICATION"

        fun isUsageRecordable(usage: String, includeSystemSounds: Boolean = false): Boolean
        {
            val u = usage.uppercase().trim()
            val regularUsage = u.contains(USAGE_UNKNOWN) || u.contains(USAGE_MEDIA) || u.contains(USAGE_GAME)
            val systemSoundUsage = includeSystemSounds && (
                u.contains(USAGE_NOTIFICATION_EVENT) ||
                    u.contains(USAGE_NOTIFICATION) ||
                    u.contains(USAGE_ASSISTANCE_SONIFICATION)
                )
            return regularUsage || systemSoundUsage
        }
    }
}
