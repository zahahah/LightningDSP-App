package lightning.dsp.utils.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun acceptsRebrandedAndLegacyReleaseIdentities() {
        assertEquals(0, ContextExtensions.validateAppIdentity("com.lightning.dsp", "LightningDSP", false))
        assertEquals(0, ContextExtensions.validateAppIdentity("me.timschneeberger.rootlessjamesdsp", "RootlessLightningDSP", false))
        assertEquals(0, ContextExtensions.validateAppIdentity("james.dsp", "JamesDSP", false))
        assertEquals(0, ContextExtensions.validateAppIdentity("me.timschneeberger.rootlessjamesdsp", "RootlessJamesDSP", false))
    }

    @Test
    fun acceptsFlavorDebugIdentitiesOnlyInDebugBuilds() {
        assertEquals(0, ContextExtensions.validateAppIdentity("com.lightning.dsp.debug", "LightningDSP", true))
        assertEquals(0, ContextExtensions.validateAppIdentity("me.timschneeberger.rootlessjamesdsp.debug", "RootlessLightningDSP", true))
        assertEquals(3, ContextExtensions.validateAppIdentity("com.lightning.dsp.debug", "LightningDSP", false))
        assertEquals(3, ContextExtensions.validateAppIdentity("me.timschneeberger.rootlessjamesdsp.debug", "RootlessLightningDSP", false))
    }

    @Test
    fun rejectsUnknownOrMismatchedIdentities() {
        assertEquals(1, ContextExtensions.validateAppIdentity("com.example.clone", "LightningDSP", false))
        assertEquals(2, ContextExtensions.validateAppIdentity("com.lightning.dsp", "RootlessLightningDSP", false))
        assertEquals(2, ContextExtensions.validateAppIdentity("me.timschneeberger.rootlessjamesdsp", "LightningDSP", false))
    }
}
