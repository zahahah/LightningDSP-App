package lightning.dsp.volumemanager.ui

/** System stream gestures remain independent of manager-owned per-app controls. */
internal class VolumePanelInteractionPolicy {
    var systemDragging = false
        private set
    private var appDragging = false
    private var pendingStreamRefresh = false

    fun startSystemDrag() { systemDragging = true }
    fun startAppDrag() { appDragging = true }
    fun finishAppDrag() { appDragging = false }

    /** Every stream refresh entry point must use this gate, including preferences and call mode. */
    fun requestStreamRefresh(): Boolean {
        if (!systemDragging) return true
        pendingStreamRefresh = true
        return false
    }

    /** Returns whether deferred stream changes need applying after the finger is released. */
    fun finishSystemDrag(): Boolean {
        systemDragging = false
        return pendingStreamRefresh.also { pendingStreamRefresh = false }
    }

    fun shouldRenderApps(managerEnabled: Boolean, perAppEnabled: Boolean): Boolean {
        if (!managerEnabled || !perAppEnabled) appDragging = false
        return !appDragging
    }
}
