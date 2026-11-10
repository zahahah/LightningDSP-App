package lightning.dsp.utils.notifications

import lightning.dsp.model.IEffectSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNotificationProjectionTest {
    private data class Session(override var uid: Int, override var packageName: String) : IEffectSession

    @Test fun rootNotificationShowsEachPackageOnceWithoutCollapsingSessionsElsewhere() {
        val sessions = arrayOf(
            Session(10322, "com.spotify.music"),
            Session(10322, "com.spotify.music"),
            Session(10323, "com.example.player"),
        )

        assertEquals(listOf("com.spotify.music", "com.example.player"),
            SessionNotificationProjection.uniqueApps(sessions, rootless = false).map { it.packageName })
        assertEquals(3, sessions.size)
    }

    @Test fun rootlessNotificationGroupsByUidAndBlankRootPackagesFallBackToUid() {
        val rootlessSessions = arrayOf(Session(12000, "first"), Session(12000, "second"))
        val rootSessions = arrayOf(Session(10322, ""), Session(10322, ""))

        assertEquals(1, SessionNotificationProjection.uniqueApps(rootlessSessions, rootless = true).size)
        assertEquals(1, SessionNotificationProjection.uniqueApps(rootSessions, rootless = false).size)
    }
}
