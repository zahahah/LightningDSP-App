package lightning.dsp.volumemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/** Bounds are shared only within a display group, so dragging cannot cross a group boundary. */
internal class VolumeReorderBounds {
    val rows = mutableMapOf<String, Rect>()
    var dragged by mutableStateOf<String?>(null)
    var pointerY by mutableFloatStateOf(0f)
    var viewport: Rect? = null
}

@Composable
internal fun VolumeReorderItem(
    id: String,
    order: List<String>,
    bounds: VolumeReorderBounds,
    listState: LazyListState?,
    editing: Boolean,
    placementModifier: Modifier = Modifier,
    onMove: (String, String) -> Unit,
    content: @Composable () -> Unit,
) {
    val latestMove = rememberUpdatedState(onMove)
    val latestOrder = rememberUpdatedState(order)
    var itemCoordinates by remember(id, bounds) { mutableStateOf<LayoutCoordinates?>(null) }
    val latestCoordinates = rememberUpdatedState(itemCoordinates)
    val dragScale by animateFloatAsState(
        targetValue = if (bounds.dragged == id) 1.025f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "volume-reorder-scale",
    )
    DisposableEffect(id, bounds) {
        onDispose {
            bounds.rows.remove(id)
            if (bounds.dragged == id) bounds.dragged = null
        }
    }
    LaunchedEffect(bounds.dragged == id, listState) {
        if (listState != null && bounds.dragged == id) {
            while (bounds.dragged == id) {
                val viewport = bounds.viewport
                if (viewport != null) {
                    val edge = 64f
                    val speed = when {
                        bounds.pointerY < viewport.top + edge -> -12f
                        bounds.pointerY > viewport.bottom - edge -> 12f
                        else -> 0f
                    }
                    if (speed != 0f) listState.scrollBy(speed)
                }
                delay(16)
            }
        }
    }
    Box(
        placementModifier
            .onGloballyPositioned {
                itemCoordinates = it
                bounds.rows[id] = it.boundsInRoot()
            }
            .background(if (bounds.dragged == id) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent)
            .graphicsLayer { scaleX = dragScale; scaleY = dragScale }
            .zIndex(if (bounds.dragged == id) 1f else 0f)
            .semantics {
                customActions = if (editing) listOf(
                    CustomAccessibilityAction("Move up") {
                        val index = latestOrder.value.indexOf(id)
                        if (index > 0) {
                            latestMove.value(id, latestOrder.value[index - 1]); true
                        } else false
                    },
                    CustomAccessibilityAction("Move down") {
                        val index = latestOrder.value.indexOf(id)
                        if (index >= 0 && index < latestOrder.value.lastIndex) {
                            latestMove.value(id, latestOrder.value[index + 1]); true
                        } else false
                    },
                ) else emptyList()
            }
            .then(if (editing) Modifier.pointerInput(id, bounds) {
                var lastPointerY = 0f
                var acceptNextMoveAt = 0L
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        bounds.dragged = id
                        lastPointerY = latestCoordinates.value?.localToRoot(start)?.y
                            ?: (bounds.rows[id]?.top ?: 0f) + start.y
                        bounds.pointerY = lastPointerY
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Use root coordinates so LazyColumn placement animations do not
                        // turn the dragged row's own movement into pointer movement.
                        val pointerY = latestCoordinates.value?.localToRoot(change.position)?.y
                            ?: (lastPointerY + dragAmount.y)
                        val moved = kotlin.math.abs(pointerY - lastPointerY) > 0.5f
                        lastPointerY = pointerY
                        if (moved) {
                            bounds.pointerY = pointerY
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now >= acceptNextMoveAt) {
                                val target = VolumeDisplayOrder.targetAfterMidpointCrossing(
                                    order = latestOrder.value,
                                    dragged = id,
                                    pointerY = pointerY,
                                    rowBounds = bounds.rows,
                                )
                                if (target != null) {
                                    latestMove.value(id, target)
                                    // Leave enough time to see each placement animation.
                                    acceptNextMoveAt = now + 260L
                                }
                            }
                        }
                    },
                    onDragEnd = { bounds.dragged = null },
                    onDragCancel = { bounds.dragged = null },
                )
            } else Modifier)
    ) { content() }
}
