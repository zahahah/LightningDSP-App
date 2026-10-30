package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumeKeyForegroundPolicy
import org.junit.Assert.*
import org.junit.Test

class VolumeKeyForegroundPolicyTest {
    @Test fun `unknown foreground fails open whenever an app excludes volume keys`() {
        val policy = VolumeKeyForegroundPolicy("lightning.dsp")
        assertFalse(policy.mayIntercept(setOf("excluded.player")))
        assertTrue(policy.mayIntercept(emptySet()))
    }

    @Test fun `only validated nonexcluded foreground may override saved exclusion`() {
        val policy = VolumeKeyForegroundPolicy("lightning.dsp")
        policy.windowChanged("excluded.player", true)
        assertFalse(policy.mayIntercept(setOf("excluded.player")))
        policy.windowChanged("another.player", true)
        assertTrue(policy.mayIntercept(setOf("excluded.player")))
        policy.windowChanged("unverified.player", false)
        assertNull(policy.foregroundPackage)
        assertFalse(policy.mayIntercept(setOf("excluded.player")))
    }

    @Test fun `SystemUI and own overlay preserve underlying excluded activity`() {
        val policy = VolumeKeyForegroundPolicy("lightning.dsp")
        policy.windowChanged("excluded.player", true)
        policy.windowChanged("com.android.systemui", false)
        assertEquals("excluded.player", policy.foregroundPackage)
        policy.windowChanged("lightning.dsp", false)
        assertEquals("excluded.player", policy.foregroundPackage)
        assertFalse(policy.mayIntercept(setOf("excluded.player")))
        policy.windowChanged("lightning.dsp", true) // Actual LightningDSP activity, not its overlay.
        assertEquals("lightning.dsp", policy.foregroundPackage)
        assertTrue(policy.mayIntercept(setOf("excluded.player")))
    }

    @Test fun `reconnect and interruption clear prior identity even after transient window`() {
        val policy = VolumeKeyForegroundPolicy("lightning.dsp")
        policy.windowChanged("another.player", true)
        policy.reset()
        policy.windowChanged("com.android.systemui", false)
        assertNull(policy.foregroundPackage)
        assertFalse(policy.mayIntercept(setOf("excluded.player")))
        policy.windowChanged("another.player", true)
        policy.reset()
        assertFalse(policy.mayIntercept(setOf("excluded.player")))
    }
}
