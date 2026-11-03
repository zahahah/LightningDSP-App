package lightning.dsp.volumemanager.backend

/** Grant only LightningDSP's media-session listener after its setting is explicitly enabled. */
internal object NotificationListenerAccessGrant {
    private const val PACKAGE = "com.lightning.dsp"
    private const val SERVICE = "me.timschneeberger.rootlessjamesdsp.service.NotificationListenerService"

    fun grant(userId: Int, hasAccess: () -> Boolean, hasRoot: () -> Boolean,
              command: (String) -> Boolean): Boolean {
        require(userId >= 0)
        if (hasAccess()) return true
        if (!hasRoot()) return false
        val component = "$PACKAGE/$SERVICE"
        if (!command("cmd notification allow_listener $component $userId")) return false
        return hasAccess()
    }
}
