package lightning.dsp.volumemanager.backend

/** Only affirmative Binder death proves that a failed player restore is no longer needed. */
internal object PlaybackCleanupPolicy {
    fun restore(binderAlive: () -> Boolean?, operation: () -> Unit): Boolean {
        fun confirmedDead(): Boolean = runCatching { binderAlive() }.getOrNull() == false
        if (confirmedDead()) return true
        return runCatching { operation() }.isSuccess || confirmedDead()
    }
}
