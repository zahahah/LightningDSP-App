package lightning.dsp.utils.logging

/** Android WARN=5. Kept platform-free so release defaults are directly testable. */
internal object DiagnosticLogPolicy {
    fun shouldLog(detailed: Boolean, priority: Int) = detailed || priority >= 5
}
