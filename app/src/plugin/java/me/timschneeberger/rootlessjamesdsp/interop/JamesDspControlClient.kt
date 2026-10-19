package me.timschneeberger.rootlessjamesdsp.interop

/** The optional privileged native-control client is intentionally root-flavor only. */
internal object JamesDspControlClient {
    fun probeAsync() = Unit
    fun setEnabledIfSupported(enabled: Boolean) = false
}
