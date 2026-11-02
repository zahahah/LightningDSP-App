package lightning.dsp.interop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-dependent transport paths are also covered by device validation;
 * keep their fail-closed status propagation explicit in host source contracts. */
class CompleteStateFenceContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun source(path: String) = File(app, "src/main/java/lightning/dsp/$path").readText()

    @Test fun disabledFileEffectsPropagateEndpointFailureToFullStateFence() {
        val base = source("interop/LightningDspBaseEngine.kt")
        assertTrue(base.contains("return setVdcInternal(false, \"\")"))
        assertTrue(base.contains("return setConvolverInternal(false, FloatArray(0), 0, 0, 0)"))
    }

    @Test fun everyBufferedRemoteUploadChecksUploadAndHashBeforeEnabling() {
        val remote = source("interop/LightningDspRemoteEngine.kt")
        for ((method, hash) in listOf("setVdcInternal" to 25001, "setConvolverInternal" to 25003,
            "setGraphicEqInternal" to 25000, "setLiveprogInternal" to 25002)) {
            val body = remote.substringAfter("override fun $method").substringBefore("\n    override fun")
            assertTrue(body.contains("!= AudioEffect.SUCCESS) return false"))
            assertTrue(body.contains("if (effect.setParameter($hash,"))
        }
        val uploads = source("utils/extensions/AudioEffectExtensions.kt")
        assertTrue(uploads.split("if (allocation != AudioEffect.SUCCESS) return allocation").size == 3)
        assertTrue(uploads.split("if (sent != AudioEffect.SUCCESS) return sent").size == 3)
    }
}
