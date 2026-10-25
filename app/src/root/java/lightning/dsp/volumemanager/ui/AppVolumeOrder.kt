package lightning.dsp.volumemanager.ui

/** One display order, projected into Active/Inactive without changing audio settings. */
internal data class AppVolumeOrder(
    val apps: List<String> = emptyList(),
    val pinned: Set<String> = emptySet(),
) {
    fun include(available: List<String>): AppVolumeOrder =
        copy(apps = (apps + available).filter(String::isNotBlank).distinct())

    fun displayOrder(available: List<String>): List<String> {
        val resolved = VolumeDisplayOrder.resolve(apps, available)
        return resolved.filter { it in pinned } + resolved.filterNot { it in pinned }
    }

    fun withPinned(app: String, enabled: Boolean): AppVolumeOrder =
        if (app.isBlank()) this else copy(pinned = if (enabled) pinned + app else pinned - app)

    /** Drag peers share pin status and activity group, including inactive pins. */
    fun movablePeers(visible: List<String>, active: Set<String>, app: String): List<String> {
        if (app !in visible) return emptyList()
        return displayOrder(visible).filter {
            (it in pinned) == (app in pinned) && (it in active) == (app in active)
        }
    }

    fun move(visible: List<String>, active: Set<String>, from: String, to: String): AppVolumeOrder {
        val peers = movablePeers(visible, active, from)
        // The same guards protect pointer moves and accessibility actions.
        if (from == to || from !in peers || to !in peers) return this
        val moved = VolumeDisplayOrder.move(peers, from, to)
        var saved = include(visible).apps
        if (from in pinned && from !in active) {
            // Inactive peers may straddle an active pin in the shared saved order.
            // Promote active pins before exchanging inactive ranks, otherwise the
            // dragged inactive app could inherit a rank above an existing active pin.
            val pins = saved.filter { it in pinned }
            val activeFirst = pins.filter { it in active } + pins.filterNot { it in active }
            saved = VolumeDisplayOrder.retainAbsent(saved, activeFirst)
        }
        return copy(apps = VolumeDisplayOrder.retainAbsent(saved, moved))
    }

    companion object {
        /** Existing Inactive ranks win; keep Active-only and temporarily absent IDs. */
        fun fromStored(
            unified: String?, pinned: Set<String>, legacyInactive: String?, legacyActive: String?,
        ): AppVolumeOrder {
            fun ids(value: String?) = value.orEmpty().split('|').filter(String::isNotBlank)
            val order = if (unified != null) ids(unified) else ids(legacyInactive) + ids(legacyActive)
            return AppVolumeOrder(order.distinct(), pinned.filter(String::isNotBlank).toSet())
        }
    }
}
