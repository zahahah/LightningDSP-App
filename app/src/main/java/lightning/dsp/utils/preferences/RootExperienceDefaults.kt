package lightning.dsp.utils.preferences

/** Seed only missing choices; an upgrade must never replace an existing processing mode. */
internal object RootExperienceDefaults {
    fun processing(existing: Map<String, *>): Map<String, Boolean> =
        if (existing.containsKey("audioformat_processing") ||
            existing.containsKey("audioformat_processing_app_mixer")) emptyMap()
        else mapOf("audioformat_processing" to false, "audioformat_processing_app_mixer" to true)

    fun manager(existing: Map<String, *>): Map<String, Boolean> =
        mapOf("enabled" to true).filterKeys { !existing.containsKey(it) }

    fun panel(existing: Map<String, *>): Map<String, Boolean> =
        mapOf("show_session_dsp_controls" to true, "show_media_controls" to true)
            .filterKeys { !existing.containsKey(it) }
}
