package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the discovery/control boundary that host JVM tests cannot invoke directly. */
class PlaybackActivityVisibilityTest {
    @Test fun playerActivityIsRecordedBeforeOptionalWritableHandle() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/root/AndroidManifest.xml").isFile }
        val source = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeRoot.kt").readText()
        val scan = source.substringAfter("for (configuration in audio.activePlaybackConfigurations)")
        assertTrue(scan.contains("PlaybackActivityPolicy.isPlaying(configuration)"))
        val activity = scan.indexOf("activityByUid[uid] =")
        val handle = scan.indexOf("getIPlayer")
        assertTrue("Playback activity must not depend on IPlayer availability", activity >= 0 && activity < handle)
    }
}
