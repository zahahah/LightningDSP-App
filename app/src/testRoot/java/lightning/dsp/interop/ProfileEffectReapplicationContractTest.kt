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

    @Test fun nativeFirProfileApplicationAlsoClearsLegacyEqualizerState() {
        val method = engine.substringAfter("override fun setMultiEqualizerInternal")
            .substringBefore("private fun applyLegacyMultiEqualizer")
        val legacyDisable = method.indexOf("effect.setParameter(1202, false.toShort())")
        val nativeWrite = method.indexOf("LightningDspControlClient.setFirEqIfSupported")

        assertTrue("A profile using native FIR must also disable stale legacy EQ", legacyDisable >= 0)
        assertTrue("Clear the legacy stage before handing its profile to native FIR",
            nativeWrite > legacyDisable)
    }

    @Test fun failedProfileNamespacesRemainDirtyAndReceiveBoundedRetries() {
        assertTrue(sync.contains("cache.markNamespaceAsChanged(it)"))
        assertTrue(sync.contains("cache.markChangesAsCommitted(successfulNamespaces)"))
        assertTrue(sync.contains("failedNamespaces.isNotEmpty() && retryAttempt < MAX_PREFERENCE_SYNC_RETRIES"))
        assertTrue(sync.contains("longArrayOf(250, 500, 1_000)"))
    }
}
