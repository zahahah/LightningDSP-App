package lightning.dsp.service

/** Boot/late-load startup must respect saved processing mode, not enable a new one. */
internal object BackgroundAudioStartupPolicy {
    const val MAX_ATTEMPTS = 15
    const val RETRY_INTERVAL_MS = 2_000L

    fun shouldOpenGlobalSession(legacyMode: Boolean, hasUsableGlobalSession: Boolean): Boolean =
        legacyMode && !hasUsableGlobalSession

    fun shouldRetry(attempt: Int, unsupported: Boolean): Boolean =
        !unsupported && attempt + 1 < MAX_ATTEMPTS
}
