package lightning.dsp.interop

enum class AAudioCompatibilityResult { APPLIED, FAILED, UNKNOWN }

/** A failed verification can still have changed the property; always verify rollback. */
object AAudioCompatibilityTransaction {
    fun apply(previous: Boolean, desired: Boolean, write: (Boolean) -> Boolean,
              persist: (Boolean) -> Boolean): AAudioCompatibilityResult {
        try {
            if (write(desired) && persist(desired)) return AAudioCompatibilityResult.APPLIED
        } catch (_: Exception) {
            // Reconcile below even if the command threw after making a change.
        }
        val propertyRestored = try { write(previous) } catch (_: Exception) { false }
        // SharedPreferences.commit can return false after changing in-memory state.
        // Roll back both stores, even if restoring the property failed.
        val preferenceRestored = try { persist(previous) } catch (_: Exception) { false }
        return if (propertyRestored && preferenceRestored) AAudioCompatibilityResult.FAILED
        else AAudioCompatibilityResult.UNKNOWN
    }
}
