package lightning.dsp.session.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepEffectsReadyPolicyTest {
    private val app = "com.example.audio"

    @Test fun retainsOnlyKnownSessionsForFocusedOrPlayingMediaAppsInSessionMode() {
        assertTrue(KeepEffectsReadyPolicy.shouldRetain(
            id = 41, packageName = app, enabled = true, globalMode = false,
            foregroundPackage = app, mediaPlayingPackages = emptySet(),
        ))
        assertTrue(KeepEffectsReadyPolicy.shouldRetain(
            id = 41, packageName = app, enabled = true, globalMode = false,
            foregroundPackage = null, mediaPlayingPackages = setOf(app),
        ))
        assertFalse(KeepEffectsReadyPolicy.shouldRetain(
            id = 41, packageName = app, enabled = true, globalMode = false,
            foregroundPackage = null, mediaPlayingPackages = emptySet(),
        ))
    }

    @Test fun neverCreatesOrKeepsSessionsForOtherAppsGlobalModeOrDisabledOption() {
        assertFalse(KeepEffectsReadyPolicy.shouldRetain(
            id = 0, packageName = app, enabled = true, globalMode = false,
            foregroundPackage = app, mediaPlayingPackages = emptySet(),
        ))
        assertFalse(KeepEffectsReadyPolicy.shouldRetain(
            id = 41, packageName = app, enabled = true, globalMode = true,
            foregroundPackage = app, mediaPlayingPackages = emptySet(),
        ))
        assertFalse(KeepEffectsReadyPolicy.shouldRetain(
            id = 41, packageName = app, enabled = false, globalMode = false,
            foregroundPackage = null, mediaPlayingPackages = setOf(app),
        ))
        assertFalse(KeepEffectsReadyPolicy.shouldRetain(
            id = 41, packageName = app, enabled = true, globalMode = false,
            foregroundPackage = null, mediaPlayingPackages = emptySet(),
        ))
        assertFalse(KeepEffectsReadyPolicy.shouldRetain(
            id = 41, packageName = "com.other.audio", enabled = true, globalMode = false,
            foregroundPackage = app, mediaPlayingPackages = setOf(app),
        ))
    }
}
