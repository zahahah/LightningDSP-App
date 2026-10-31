package lightning.dsp.interop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract for profile application paths that require AudioEffect/native integration. */
class ProfileEffectReapplicationContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private val engine = File(app,
        "src/main/java/lightning/dsp/interop/LightningDspRemoteEngine.kt").readText()
    private val sync = File(app,
        "src/main/java/lightning/dsp/interop/LightningDspBaseEngine.kt").readText()

    @Test fun regainedControlReplaysAllNamespacesInsteadOfOnlyNativeControls() {
        val method = engine.substringAfter("private fun watchControl")
            .substringBefore("override fun syncWithPreferences")
        assertTrue(method.contains("DspDesiredState.replace"))
        assertTrue(method.contains("Constants.ACTION_PRESET_LOADED"))
        assertTrue(method.contains("effect !== attached"))
    }

    @Test fun failedProfileNamespacesRemainDirtyAndReceiveBoundedRetries() {
        assertTrue(sync.contains("cache.markNamespaceAsChanged(it)"))
        assertTrue(sync.contains("cache.markChangesAsCommitted(successfulNamespaces)"))
        assertTrue(sync.contains("failedNamespaces.isNotEmpty() && retryAttempt < MAX_PREFERENCE_SYNC_RETRIES"))
        assertTrue(sync.contains("longArrayOf(250, 500, 1_000)"))
    }
}
