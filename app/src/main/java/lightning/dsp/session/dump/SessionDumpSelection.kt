package lightning.dsp.session.dump

import lightning.dsp.session.dump.data.ISessionInfoDump

/** Empty playback is a successful observation, not an unavailable collector. */
internal object SessionDumpSelection {
    fun collect(
        preferred: () -> ISessionInfoDump?,
        alternatives: Iterable<() -> ISessionInfoDump?>,
        allowFallback: Boolean,
        onFailure: (Exception) -> Unit,
    ): ISessionInfoDump? {
        fun read(provider: () -> ISessionInfoDump?): ISessionInfoDump? = try {
            provider()
        } catch (failure: Exception) {
            onFailure(failure)
            null
        }

        val first = read(preferred)
        if (!allowFallback || first?.sessions?.isNotEmpty() == true) return first
        var empty = first
        for (provider in alternatives) {
            val result = read(provider) ?: continue
            if (result.sessions.isNotEmpty()) return result
            if (empty == null) empty = result
        }
        return empty
    }
}
