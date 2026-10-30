package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumeDisplayOrder
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VolumeDisplayOrderTest {
    @Test fun restoredOrderDropsUnknownDuplicatesAndAppendsNewControls() {
        assertEquals(listOf("b", "a", "c"), VolumeDisplayOrder.resolve(
            listOf("b", "missing", "b"), listOf("a", "b", "c", "a")))
    }

    @Test fun movesAreBoundedToTheCurrentGroup() {
        val active = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), VolumeDisplayOrder.move(active, "a", "c"))
        assertEquals(listOf("c", "a", "b"), VolumeDisplayOrder.move(active, "c", "a"))
        assertEquals(active, VolumeDisplayOrder.move(active, "a", "inactive-app"))
        assertEquals(active, VolumeDisplayOrder.move(active, "missing", "b"))
    }

    @Test fun dragMovesOnlyAfterCrossingAdjacentRowMidpoint() {
        val order = listOf("above", "dragged", "below")
        val rows = mapOf(
            "above" to Rect(0f, 0f, 100f, 100f),
            "dragged" to Rect(0f, 110f, 100f, 210f),
            "below" to Rect(0f, 220f, 100f, 320f),
        )

        assertNull(VolumeDisplayOrder.targetAfterMidpointCrossing(order, "dragged", 149f, rows))
        assertEquals("above", VolumeDisplayOrder.targetAfterMidpointCrossing(order, "dragged", 50f, rows))
        assertNull(VolumeDisplayOrder.targetAfterMidpointCrossing(order, "dragged", 211f, rows))
        assertEquals("below", VolumeDisplayOrder.targetAfterMidpointCrossing(order, "dragged", 270f, rows))
        assertNull(VolumeDisplayOrder.targetAfterMidpointCrossing(order, "missing", 270f, rows))
    }

    @Test fun persistingAppMoveUsesTheRenderedOrderInsteadOfAlphabeticalCatalog() {
        val rendered = listOf("zebra.app", "alpha.app", "middle.app")
        val persisted = VolumeDisplayOrder.move(rendered, "zebra.app", "middle.app")

        assertEquals(listOf("alpha.app", "middle.app", "zebra.app"), persisted)
        assertEquals(persisted, VolumeDisplayOrder.resolve(persisted,
            listOf("alpha.app", "middle.app", "zebra.app")))
    }

    @Test fun removedSecondaryStreamStaysOutsideCollapsedDropdown() {
        val order = listOf("media", "call", "ring", "alarm", "notifications")
        val selected = setOf("media", "call", "ring", "alarm", "notifications")

        assertEquals(listOf("media"), VolumeDisplayOrder.visibleSystemStreams(
            order, selected, "media", grouping = true, expanded = false,
            editing = false, showVisibilityOptions = false))
        assertEquals(listOf("media", "call"), VolumeDisplayOrder.visibleSystemStreams(
            order, selected - "call", "media", grouping = true, expanded = false,
            editing = false, showVisibilityOptions = false))
        assertEquals(order, VolumeDisplayOrder.visibleSystemStreams(
            order, selected - "call", "media", grouping = true, expanded = true,
            editing = false, showVisibilityOptions = false))
        assertEquals(listOf("media", "ring", "alarm", "notifications"), VolumeDisplayOrder.visibleSystemStreams(
            order, selected - "call", "media", grouping = false, expanded = false,
            editing = false, showVisibilityOptions = false))
    }

    @Test fun savingFilteredGroupRetainsTemporarilyAbsentPlayers() {
        val saved = listOf("a", "temporarily-inactive", "b")
        val moved = listOf("b", "a", "new-app")
        assertEquals(listOf("b", "temporarily-inactive", "a", "new-app"),
            VolumeDisplayOrder.retainAbsent(saved, moved))
        assertEquals(listOf("b", "a"), VolumeDisplayOrder.resolve(
            VolumeDisplayOrder.retainAbsent(saved, listOf("b", "a")), listOf("a", "b")))
    }

    @Test fun sharedAppOrderAndStreamPersistenceDoNotModifyAudioSettings() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val ui = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningVolumeComposeUi.kt").readText()
        assertTrue(ui.contains("val savedAppOrder by rememberAppVolumeOrder(panelPrefs)"))
        assertTrue(ui.contains("val activeApps = visibleApps.filter { it.active }"))
        assertTrue(ui.contains("val recentApps = visibleApps.filter { !it.active && it.packageName in recentIds }"))
        assertTrue(ui.contains("val inactiveApps = visibleApps.filterNot { it.active || it.packageName in recentIds }"))
        assertTrue(ui.contains("Triple(\"Recent\", recentApps, recentBounds)"))
        assertTrue(ui.contains("appOrder.movablePeers("))
        assertTrue(ui.contains("editing = editing && peers.size > 1"))
        assertTrue(ui.contains("group_system_streams\", true"))
        assertTrue(ui.contains("Icons.Default.ExpandLess"))
        assertTrue(ui.contains("Icons.Default.ExpandMore"))
        assertTrue(ui.contains("else Icons.Default.Tune"))
        assertFalse(ui.contains("Icons.Default.Edit"))
        assertTrue(ui.contains("placementSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)"))
        assertTrue(ui.contains("VolumeDisplayOrder.visibleSystemStreams("))
        assertTrue(ui.contains("VolumeDisplayOrder.move(streamIds, from, to)"))
        assertTrue(ui.contains("showDspCard"))
        assertTrue(ui.contains("Hide LightningDSP card"))
        assertTrue(ui.contains("Back to LightningDSP"))
        assertTrue(ui.contains("longPressTimeoutMillis"))
        assertTrue(ui.contains("enabled = controllable && !editing"))
    }

    @Test fun dragCoordinatesCannotSelectAnotherGroupOrBecomeVolumeTaps() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val drag = File(app, "src/root/java/lightning/dsp/volumemanager/ui/VolumeReorderItem.kt").readText()
        assertTrue(drag.contains("targetAfterMidpointCrossing"))
        assertTrue(drag.contains("var acceptNextMoveAt = 0L"))
        assertTrue(drag.contains("val moved = kotlin.math.abs(pointerY - lastPointerY) > 0.5f"))
        assertTrue(drag.contains(".then(if (editing) Modifier.pointerInput"))
        assertFalse(drag.contains("onEditing"))
        assertTrue(drag.contains("detectDragGesturesAfterLongPress("))
        assertTrue(drag.contains("onDragStart = { start ->"))
        assertTrue(drag.contains("onDragCancel = { bounds.dragged = null }"))
        assertTrue(drag.contains("change.consume()"))
        assertTrue(drag.contains("bounds.dragged = null"))
        assertTrue(drag.contains("Move up"))
        assertTrue(drag.contains("Move down"))
        assertTrue(drag.contains("listState.scrollBy(speed)"))
    }
}
