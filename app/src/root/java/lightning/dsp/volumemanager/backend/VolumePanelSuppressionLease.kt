package lightning.dsp.volumemanager.backend

/** Short-lived SystemUI opt-in; expires automatically if the root volume bridge disappears. */
internal object VolumePanelSuppressionLease {
    const val PROPERTY = "debug.lightningdsp.volume_panel_lease_ms"
    const val REFRESH_INTERVAL_MS = 2_000L
    const val TTL_MS = 6_000L

    fun shouldSuppress(root: Boolean, active: Boolean, panelEnabled: Boolean, controllerReady: Boolean, bypass: Boolean): Boolean =
        root && active && panelEnabled && controllerReady && !bypass

    fun propertyValue(enabled: Boolean, uptimeMillis: Long): String =
        if (enabled && uptimeMillis > 0L) uptimeMillis.toString() else "0"

    fun isActive(propertyValue: String?, uptimeMillis: Long): Boolean {
        val refreshedAt = propertyValue?.toLongOrNull() ?: return false
        return refreshedAt > 0L && uptimeMillis >= refreshedAt && uptimeMillis - refreshedAt <= TTL_MS
    }
}
