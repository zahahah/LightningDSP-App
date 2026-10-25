package lightning.dsp.volumemanager.ui

/** Optional floating-panel gesture; the ordinary chevron remains the default everywhere. */
internal object MediaGroupExpansionPolicy {
    // Retain the original stored key so existing choices and backups survive the gesture change.
    const val PREFERENCE_KEY = "media_icon_hold_expand"

    fun useIconTap(floating: Boolean, enabled: Boolean, grouping: Boolean, editing: Boolean): Boolean =
        floating && enabled && grouping && !editing

    fun showChevron(grouping: Boolean, expanded: Boolean, iconTap: Boolean, mediaVisible: Boolean): Boolean =
        grouping && (!iconTap || expanded || !mediaVisible)
}
