package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level bridge contracts, not Android Binder or firmware runtime validation. */
class HardwareVolumeControllerContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private val controller = File(app,
        "src/root/java/lightning/dsp/volumemanager/backend/LightningDspRootVolumeController.kt").readText()

    @Test fun replacementReportsAdjustmentReadinessAfterOwnershipIsVerified() {
        val lookup = controller.indexOf("\"notifyVolumeControllerVisible\", contract")
        val register = controller.indexOf("set.invoke(audio, proxy)")
        val verify = controller.indexOf("check((get.invoke(audio) as? IInterface)?.asBinder() == controller.callback)")
        val ready = controller.indexOf("notifyVisible.invoke(audio, proxy, true)")
        assertTrue("Resolve visibility API before replacing SystemUI", lookup >= 0 && lookup < register)
        assertTrue("Only report readiness for the installed controller", register < verify && verify < ready)
        assertTrue("Fail closed if registration or visibility fails",
            controller.substring(ready).contains("runCatching { controller.close() }"))
    }

    @Test fun panelCallbacksNeverReplayHardwareStepsAlreadyHandledByAudioService() {
        val callback = controller.substringBefore("/** Do not overwrite")
        assertFalse(callback.contains("adjustSuggestedStreamVolume"))
        assertFalse(callback.contains("adjustStreamVolume"))
        assertFalse(callback.contains("setStreamVolume"))
        assertTrue(callback.contains("previousBinder?.transact(code, forwarded, reply, flags)"))
        assertTrue(callback.contains("volumeFlags and AudioManager.FLAG_SHOW_UI.inv()"))
        assertTrue(callback.contains("if (bypass) volumeFlags"))
        assertTrue("Keep safe-volume/dose warnings and vendor callbacks intact",
            callback.contains("return previousBinder.transact(code, data, reply, flags)"))
    }

    @Test fun restoreDoesNotClobberANewerOwnerOrLeaveVisibilityInItsState() {
        val close = controller.substringAfter("override fun close()").substringBefore("companion object")
        assertTrue(close.contains("if (current?.asBinder() == callback) setController.invoke(audio, previous)"))
        assertFalse("Framework registration resets visibility; do not mutate another owner", close.contains("notifyVisible.invoke"))
        assertTrue(close.contains("if (closed) return"))
        assertTrue(controller.contains("check(android.os.Process.myUid() == 0)"))
    }
}
