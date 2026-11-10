package lightning.dsp.session.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundActivityTrackerTest {
    @Test fun pausingOneActivityDoesNotDropAnotherResumedActivityInSameApp() {
        val tracker = ForegroundActivityTracker()
        tracker.onResumed("ru.woesss.j2meloader", "MainActivity#1")
        tracker.onResumed("ru.woesss.j2meloader", "MicroActivity#2")

        tracker.onPaused("ru.woesss.j2meloader", "MainActivity#1")

        assertEquals("ru.woesss.j2meloader", tracker.foregroundPackage)
    }

    @Test fun mostRecentlyResumedPackageRemainsFocusedUntilItsActivityPauses() {
        val tracker = ForegroundActivityTracker()
        tracker.onResumed("ru.woesss.j2meloader", "MainActivity#1")
        tracker.onResumed("com.termux", "TermuxActivity#2")
        tracker.onPaused("ru.woesss.j2meloader", "MainActivity#1")

        assertEquals("com.termux", tracker.foregroundPackage)
        tracker.onPaused("com.termux", "TermuxActivity#2")
        assertNull(tracker.foregroundPackage)
    }

    @Test fun clearingUsageAccessStateDoesNotRetainStaleFocus() {
        val tracker = ForegroundActivityTracker()
        tracker.onResumed("ru.woesss.j2meloader", "MicroActivity#2")
        tracker.clear()
        assertNull(tracker.foregroundPackage)
    }
}
