package lightning.dsp.volumemanager.backend

/** Only grant our own overlay app-op after an explicit floating-panel request. */
internal object OverlayPermissionGrant {
    fun grant(packageName: String, userId: Int, allowed: () -> Boolean,
              hasRoot: () -> Boolean, command: (String) -> Boolean): Boolean {
        require(packageName == "com.lightning.dsp" && userId >= 0)
        if (allowed()) return true
        if (!hasRoot()) return false
        if (!command("cmd appops set --user $userId $packageName SYSTEM_ALERT_WINDOW allow")) return false
        // Command acceptance is not proof that Android permits an overlay.
        return allowed()
    }
}
