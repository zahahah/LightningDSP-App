package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.AppVolumeOrder
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class AppVolumeOrderTest {
    @Test fun oldInactiveOrderSeedsBothGroupsAndRetainsActiveOnlyAndAbsentApps() {
        val order = AppVolumeOrder.fromStored(null, emptySet(), "z|a|absent|z", "a|b")
        assertEquals(listOf("z", "a", "absent", "b"), order.apps)
        assertEquals(listOf("z", "a", "b"), order.displayOrder(listOf("a", "b", "z")))
    }

    @Test fun newSavedOrderWinsOverBothLegacyOrdersEvenWhenEmpty() {
        assertEquals(emptyList<String>(), AppVolumeOrder.fromStored("", emptySet(), "a", "b").apps)
        assertEquals(listOf("b", "a"), AppVolumeOrder.fromStored("b|a", emptySet(), "a|b", "a|b").apps)
    }

    @Test fun inactiveMoveIsTheOrderWhenThoseAppsBecomeActive() {
        val order = AppVolumeOrder(listOf("a", "b", "c"))
        val moved = order.move(listOf("a", "b", "c"), emptySet(), "c", "a")
        assertEquals(listOf("c", "a", "b"), moved.displayOrder(listOf("a", "b", "c")))
        // The active subset and the full inactive list are projections of the same order.
        assertEquals(listOf("c", "b"), moved.displayOrder(listOf("b", "c")))
        assertEquals(listOf("c", "a", "b"), moved.displayOrder(listOf("a", "b", "c")))
    }

    @Test fun activeMoveAlsoSurvivesStopAndRestart() {
        val order = AppVolumeOrder(listOf("a", "inactive", "b"))
        val moved = order.move(listOf("a", "b"), setOf("a", "b"), "b", "a")
        assertEquals(listOf("b", "inactive", "a"), moved.apps)
        val restored = AppVolumeOrder.fromStored(moved.apps.joinToString("|"), moved.pinned, "a|b", "a|b")
        assertEquals(moved, restored)
        assertEquals(listOf("b", "inactive", "a"), restored.displayOrder(listOf("a", "b", "inactive")))
    }

    @Test fun pinIsOffByDefaultAndPrecedesNewlyActiveUnpinnedApps() {
        val original = AppVolumeOrder(listOf("new-player", "player"))
        assertTrue(original.pinned.isEmpty())
        val pinned = original.withPinned("player", true)
        assertEquals(listOf("player"), pinned.displayOrder(listOf("player")))
        assertEquals(listOf("player", "new-player"), pinned.displayOrder(listOf("new-player", "player")))
        assertEquals(original, pinned.withPinned("player", false))
    }

    @Test fun activePinsReorderOnlyWithOtherActivePins() {
        val order = AppVolumeOrder(listOf("p1", "p2", "p3", "regular"), setOf("p1", "p2", "p3"))
        val active = setOf("p1", "p3", "regular")
        assertEquals(listOf("p1", "p3"), order.movablePeers(listOf("regular", "p3", "p1"), active, "p1"))
        assertEquals(listOf("p2"), order.movablePeers(listOf("p2"), active, "p2"))
        val moved = order.move(listOf("p1", "p3", "regular"), active, "p3", "p1")
        assertEquals(listOf("p3", "p2", "p1", "regular"), moved.apps)
        assertEquals(listOf("p3", "p2", "p1", "regular"), moved.displayOrder(order.apps))
    }

    @Test fun inactivePinsCanReorderAndKeepTheirOrderWhenTheyBecomeActive() {
        val order = AppVolumeOrder(listOf("p1", "p2", "p3", "regular"), setOf("p1", "p2", "p3"))
        assertEquals(listOf("p1", "p2", "p3"), order.movablePeers(order.apps, emptySet(), "p2"))
        val moved = order.move(order.apps, emptySet(), "p3", "p1")
        assertEquals(listOf("p3", "p1", "p2", "regular"), moved.apps)
        assertEquals(listOf("p3", "p1"), moved.displayOrder(listOf("p1", "p3")))
    }

    @Test fun inactivePinCannotLeapfrogAnActivePinThroughAnotherInactivePin() {
        val order = AppVolumeOrder(listOf("i1", "a1", "regular", "i2", "a2", "absent"),
            setOf("i1", "a1", "i2", "a2", "absent"))
        val moved = order.move(listOf("i1", "i2"), setOf("a1", "a2"), "i2", "i1")
        assertEquals(listOf("a1", "a2", "regular", "i2", "i1", "absent"), moved.apps)
        assertEquals(order.pinned, moved.pinned)
        // Starting playback after this edit must not put i2 ahead of either existing active pin.
        assertEquals(listOf("a1", "a2", "i2"), moved.displayOrder(listOf("i2", "a1", "a2")))
        val restored = AppVolumeOrder.fromStored(moved.apps.joinToString("|"), moved.pinned, null, null)
        assertEquals(moved, restored)
    }

    @Test fun invalidInactivePinMovesDoNotPromoteOrChangeAnyRanks() {
        val order = AppVolumeOrder(listOf("inactive", "active", "other"), setOf("inactive", "active", "other"))
        assertEquals(order, order.move(order.apps, setOf("active"), "inactive", "inactive"))
        assertEquals(order, order.move(order.apps, setOf("active"), "inactive", "active"))
        assertEquals(order, order.move(listOf("inactive"), setOf("active"), "inactive", "other"))
    }

    @Test fun neitherGesturesNorAccessibilityCanCrossPinOrActivityBoundaries() {
        val order = AppVolumeOrder(listOf("p1", "p2", "a", "b"), setOf("p1", "p2"))
        val active = setOf("p1", "a")
        for ((from, to) in listOf("p1" to "a", "a" to "p1", "p1" to "p2", "a" to "b")) {
            assertEquals(order, order.move(order.apps, active, from, to))
        }
        assertEquals(order, order.move(listOf("a"), setOf("a", "b"), "a", "b"))
        assertEquals(order, order.move(order.apps, active, "missing", "a"))
    }

    @Test fun regularMovesPreserveEveryPinnedHiddenAndAbsentSlot() {
        val order = AppVolumeOrder(listOf("a", "pin", "hidden", "b", "absent"), setOf("pin"))
        val moved = order.move(listOf("pin", "a", "b"), emptySet(), "b", "a")
        assertEquals(listOf("b", "pin", "hidden", "a", "absent"), moved.apps)
        assertEquals(listOf("pin", "b", "a"), moved.displayOrder(listOf("a", "b", "pin")))
    }

    @Test fun newAppsAppendWithoutDiscardingSavedRanksOrPins() {
        val order = AppVolumeOrder(listOf("saved", "absent"), setOf("absent"))
            .include(listOf("new", "saved", "new", ""))
        assertEquals(listOf("saved", "absent", "new"), order.apps)
        assertEquals(setOf("absent"), order.pinned)
        assertEquals(listOf("absent", "saved", "new"), order.displayOrder(listOf("new", "absent", "saved")))
    }

    @Test fun orderAndPinsInvalidateDisplayWithoutPlayerOrVolumeChanges() {
        val saved = mutableStateOf(AppVolumeOrder(listOf("a", "b", "c")))
        val unchangedCatalog = listOf("a", "b", "c")
        val rows = derivedStateOf { saved.value.displayOrder(unchangedCatalog) }
        assertEquals(unchangedCatalog, rows.value)
        saved.value = saved.value.move(unchangedCatalog, emptySet(), "c", "a")
        assertEquals(listOf("c", "a", "b"), rows.value)
        saved.value = saved.value.withPinned("b", true)
        assertEquals(listOf("b", "c", "a"), rows.value)
        saved.value = saved.value.withPinned("b", false)
        assertEquals(listOf("c", "a", "b"), rows.value)
    }
}
