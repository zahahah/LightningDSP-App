package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static contract only; target-method presence and panel behavior require a One UI device. */
class SamsungVolumePanelHookContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }

    @Test fun suppressionIsSystemUiScopedAndLeaseGated() {
        val hook = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/SamsungVolumePanelHook.java").readText()
        val lease = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/VolumePanelSuppressionLease.kt").readText()
        val root = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeRoot.kt").readText()
        assertTrue(hook.contains("com.android.systemui.volume.VolumeDialogControllerImpl"))
        assertTrue(hook.contains("com.android.systemui.volume.VolumeDialogControl"))
        assertTrue(hook.contains("hookAllMethods(control, \"onVolumeChangedW\""))
        assertTrue(hook.contains("if (!suppressionActive()"))
        assertTrue(hook.contains("param.args[1] = flags & ~FLAG_SHOW_UI"))
        assertTrue(hook.contains("now - refreshedAt <= LEASE_TTL_MS"))
        assertTrue(lease.contains("uptimeMillis - refreshedAt <= TTL_MS"))
        assertTrue(root.contains("settings.optBoolean(\"panel_enabled\")"))
        assertTrue(root.contains("VolumePanelSuppressionLease.PROPERTY, \"0\")"))
    }
}
