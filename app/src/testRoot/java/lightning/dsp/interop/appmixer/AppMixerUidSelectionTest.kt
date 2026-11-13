package lightning.dsp.interop.appmixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMixerUidSelectionTest {
    private fun app(uid: Int, name: String, enabled: Boolean = true) = AppMixerUidSelection.App(uid, name, enabled)

    @Test fun sharedUidIsExcludedIfAnyPackageOptsOutOrIsUnknown() {
        val apps = listOf(app(10001, "one"), app(10002, "two"), app(10002, "other", false),
            app(10003, "known"), app(10003, ""), app(1000, "system"), app(10004, "relay"))
        assertEquals(setOf(10001), AppMixerUidSelection.select(apps, relayUid = 10004))
    }

    @Test fun multiUserApplicationUidsAreDistinctAndIncludedByDefault() {
        val apps = listOf(app(10001, "a"), app(110001, "a"))
        assertEquals(setOf(10001, 110001), AppMixerUidSelection.select(apps, relayUid = 10002))
    }

    @Test fun exclusionsContainRootRelayAndAnySharedUidWithAnOptOut() {
        val apps = listOf(app(10001, "enabled"), app(10002, "one"), app(10002, "other", false))
        assertEquals(setOf(0, 10004, 10002), AppMixerUidSelection.exclusions(apps, 10004))
    }

    @Test fun excessiveOrUnknownExclusionsFailClosed() {
        assertNull(AppMixerUidSelection.exclusions(listOf(app(10001, "")), 10004))
        assertNull(AppMixerUidSelection.exclusions((10010..10025).map { app(it, "$it", false) }, 10004))
    }

    @Test fun systemSoundCriteriaReserveRoomForAdditionalRuleCriteria() {
        val disabled = (10010..10021).map { app(it, "$it", false) }
        assertEquals(14, AppMixerUidSelection.exclusions(disabled, 10004)?.size)
        assertNull(AppMixerUidSelection.exclusions(disabled + app(10022, "10022", false), 10004, true))
    }
}
