package lightning.dsp.volumemanager.ui

/** A synchronous initial LiveData value can predate the user's persisted master toggle. */
internal object VolumeOverlayLifecyclePolicy {
    enum class StateAction { IGNORE, HIDE, RENDER }

    fun stateAction(currentHost: Boolean, masterOptIn: Boolean, observedEnabled: Boolean): StateAction = when {
        !currentHost -> StateAction.IGNORE
        !masterOptIn -> StateAction.HIDE
        !observedEnabled -> StateAction.IGNORE
        else -> StateAction.RENDER
    }

    fun mayScheduleDismiss(currentHost: Boolean, attached: Boolean) = currentHost && attached
}
