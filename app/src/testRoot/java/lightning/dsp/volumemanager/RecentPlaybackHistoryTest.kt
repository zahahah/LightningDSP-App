package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.RecentPlaybackHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPlaybackHistoryTest {
    @Test fun stoppedAppsAppearMostRecentFirstWithoutDuplicatingActiveApps() {
        val history = RecentPlaybackHistory()
        val available = setOf("a", "b", "c")
        assertEquals(emptyList<String>(), history.update(listOf("a"), available))
        assertEquals(listOf("a"), history.update(emptyList(), available))
        assertEquals(emptyList<String>(), history.update(listOf("a"), available))
        assertEquals(emptyList<String>(), history.update(listOf("a", "b"), available))
        assertEquals(listOf("b"), history.update(listOf("a"), available))
        assertEquals(listOf("a", "b"), history.update(emptyList(), available))
    }

    @Test fun historyIsBoundedAndDropsUnavailableApps() {
        val history = RecentPlaybackHistory()
        val available = (1..7).map { "app$it" }.toSet()
        (1..7).forEach { number ->
            history.update(listOf("app$number"), available)
            history.update(emptyList(), available)
        }
        assertEquals(listOf("app7", "app6", "app5", "app4", "app3"),
            history.update(emptyList(), available))
        assertEquals(listOf("app7", "app5", "app4", "app3"),
            history.update(emptyList(), available - "app6"))
    }

    @Test fun repeatedSnapshotsDoNotReorderRecentApps() {
        val history = RecentPlaybackHistory()
        val available = setOf("a", "b")
        history.update(listOf("a"), available)
        history.update(emptyList(), available)
        history.update(listOf("b"), available)
        assertEquals(listOf("a"), history.update(listOf("b"), available))
        assertEquals(listOf("b", "a"), history.update(emptyList(), available))
    }
}
