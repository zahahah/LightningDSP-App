package lightning.dsp.utils.preferences

/** Seed only missing choices; an upgrade must never replace an existing processing mode. */
internal object RootExperienceDefaults {
    fun processing(existing: Map<String, *>, sdkInt: Int): Map<String, Boolean> {
        if (existing.containsKey("audioformat_processing") ||
            existing.containsKey("audioformat_processing_app_mixer")) return emptyMap()

        // Global Mode is the safer default on Android 14/15; keep Fusion as
        // the default on newer releases while leaving all modes selectable.
        return if (sdkInt == 34 || sdkInt == 35) {
            mapOf("audioformat_processing" to true, "audioformat_processing_app_mixer" to false)
        } else {
            mapOf("audioformat_processing" to false, "audioformat_processing_app_mixer" to true)
        }
    }

    fun manager(existing: Map<String, *>): Map<String, Boolean> =
        mapOf("enabled" to true).filterKeys { !existing.containsKey(it) }

    fun panel(existing: Map<String, *>): Map<String, Boolean> =
        mapOf("show_session_dsp_controls" to true, "show_media_controls" to true)
            .filterKeys { !existing.containsKey(it) }
}
