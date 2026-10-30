package lightning.dsp.volumemanager

import java.io.File
import lightning.dsp.volumemanager.backend.PlaybackPlatformAccess
import org.junit.Assert.*
import org.junit.Test

/** JVM reflection coverage does not demonstrate Android hidden-API availability. */
class PlaybackReflectionContractTest {
    private class PlaybackConfigurationFixture(private val player: Any?) {
        @Suppress("unused")
        private fun getIPlayer(): Any? = player
    }

    @Test fun nonPublicPlayerAccessorCanBeInvoked() {
        val player = Any()
        assertSame(player, PlaybackPlatformAccess.invokeNoArg(
            PlaybackConfigurationFixture(player), "getIPlayer"))
    }

    @Test fun missingPlayerStaysNullRatherThanBecomingAUsableContext() {
        assertNull(PlaybackPlatformAccess.invokeNoArg(
            PlaybackConfigurationFixture(null), "getIPlayer"))
    }

    @Test fun missingAccessorIsNotReportedAsSuccess() {
        assertThrows(NoSuchMethodException::class.java) {
            PlaybackPlatformAccess.invokeNoArg(PlaybackConfigurationFixture(Any()), "unavailableAccessor")
        }
    }

    @Test fun rootPlayerDiscoveryUsesTheTestedAccessibilityPath() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/root/AndroidManifest.xml").isFile }
        val source = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeRoot.kt").readText()
        assertTrue(source.contains("PlaybackPlatformAccess.invokeNoArg(configuration, \"getIPlayer\")"))
    }
}
