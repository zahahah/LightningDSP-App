package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumePanelInteractionPolicy
import org.junit.Assert.*
import org.junit.Test

class VolumePanelInteractionPolicyTest {
    @Test fun `system drag survives every master and per-app combination`() {
        for (manager in listOf(false, true)) for (perApp in listOf(false, true)) {
            val policy = VolumePanelInteractionPolicy()
            policy.startSystemDrag()
            assertTrue(policy.shouldRenderApps(manager, perApp))
            repeat(3) { assertFalse(policy.requestStreamRefresh()) }
            assertTrue(policy.systemDragging)
            assertTrue(policy.finishSystemDrag())
            assertTrue(policy.requestStreamRefresh())
        }
    }

    @Test fun `broadcast preference and mode stream refresh requests coalesce until release`() {
        val policy = VolumePanelInteractionPolicy()
        policy.startSystemDrag()
        assertFalse(policy.requestStreamRefresh()) // volume broadcast
        assertFalse(policy.requestStreamRefresh()) // stream preference
        assertFalse(policy.requestStreamRefresh()) // periodic call-mode change
        assertTrue(policy.finishSystemDrag())
        assertFalse(policy.finishSystemDrag())
        assertTrue(policy.requestStreamRefresh())
    }

    @Test fun `app drag can defer app render but switches off immediately remove app controls`() {
        for (manager in listOf(false, true)) for (perApp in listOf(false, true)) {
            val policy = VolumePanelInteractionPolicy()
            policy.startAppDrag()
            assertEquals(!(manager && perApp), policy.shouldRenderApps(manager, perApp))
            assertTrue(policy.requestStreamRefresh())
            policy.finishAppDrag()
            assertTrue(policy.shouldRenderApps(manager, perApp))
        }
    }

    @Test fun `disabling app control cannot clear an independent system drag`() {
        val policy = VolumePanelInteractionPolicy()
        policy.startSystemDrag()
        policy.startAppDrag()
        assertTrue(policy.shouldRenderApps(false, false))
        assertTrue(policy.systemDragging)
        assertFalse(policy.requestStreamRefresh())
        assertTrue(policy.finishSystemDrag())
    }
}
