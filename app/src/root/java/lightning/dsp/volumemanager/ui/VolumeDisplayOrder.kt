package lightning.dsp.volumemanager.ui

import androidx.compose.ui.geometry.Rect

/** Display-only ordering. Never changes audio routing or the DSP processing chain. */
internal object VolumeDisplayOrder {
    fun resolve(saved: List<String>, available: List<String>): List<String> {
        val known = available.distinct()
        return (saved.filter { it in known } + known).distinct()
    }

    fun move(order: List<String>, dragged: String, target: String): List<String> {
        val from = order.indexOf(dragged)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Move only after the pointer crosses the midpoint of an adjacent visible row. */
    fun targetAfterMidpointCrossing(
        order: List<String>,
        dragged: String,
        pointerY: Float,
        rowBounds: Map<String, Rect>,
    ): String? {
        val index = order.indexOf(dragged)
        if (index < 0) return null
        val previous = order.getOrNull(index - 1)
        if (previous != null && rowBounds[previous]?.let { pointerY <= it.center.y } == true) {
            return previous
        }
        val next = order.getOrNull(index + 1)
        if (next != null && rowBounds[next]?.let { pointerY >= it.center.y } == true) {
            return next
        }
        return null
    }

    /**
     * Secondary streams removed from the collapsible group remain visible as
     * standalone rows. With grouping disabled, visibility toggles keep their
     * usual hide/show meaning.
     */
    fun visibleSystemStreams(
        order: List<String>,
        selected: Set<String>,
        mediaId: String,
        grouping: Boolean,
        expanded: Boolean,
        editing: Boolean,
        showVisibilityOptions: Boolean,
    ): List<String> = order.filter { id ->
        val isSelected = id in selected
        val outsideCollapsedGroup = grouping && id != mediaId && !isSelected
        (isSelected || showVisibilityOptions || editing || outsideCollapsedGroup) &&
            (!grouping || expanded || editing || id == mediaId || !isSelected)
    }

    /** Preserve temporarily absent players and hidden rows when saving a filtered group. */
    fun retainAbsent(saved: List<String>, visible: List<String>): List<String> {
        val remaining = visible.distinct().toMutableList()
        val result = saved.distinct().mapNotNull { id ->
            if (id in visible) remaining.removeFirstOrNull() else id
        }
        return (result + remaining).distinct()
    }
}
