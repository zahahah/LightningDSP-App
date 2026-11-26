package lightning.dsp.xposed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedTransportContractTest {
    private val app = File(".")

    @Test fun entrypointsAndNativeRegistrationAreDefinedSeparately() {
        val legacy = app.resolve("src/xposedLegacy/assets")
        val modern = app.resolve("src/xposedModern/resources/META-INF/xposed")
        assertEquals("lightning.dsp.xposed.LegacyHookModule", legacy.resolve("xposed_init").readText().trim())
        assertEquals("lightning.dsp.xposed.ModernHookModule", modern.resolve("java_init.list").readText().trim())
        assertEquals("libldsp_aaudio_compat.so", legacy.resolve("native_init").readText().trim())
        assertEquals(legacy.resolve("native_init").readText(), modern.resolve("native_init.list").readText())
        val config = modern.resolve("module.prop").readLines()
        assertTrue(config.containsAll(listOf("minApiVersion=101", "targetApiVersion=102",
            "staticScope=false", "autoHotReload=false")))
        assertFalse(app.resolve("src/root/assets/xposed_init").exists())
        assertFalse(app.resolve("src/root/assets/native_init").exists())
    }

    @Test fun shortCircuitDistinguishesNullResultFromProceed() {
        val receiver = Any()
        val arguments = arrayOf<Any>("original")
        val call = HookBackend.Call(receiver, arguments)
        assertFalse(call.shortCircuited())
        assertEquals(receiver, call.receiver)
        call.args[0] = "changed"
        assertEquals("changed", arguments[0])
        call.setResult(null)
        assertTrue(call.shortCircuited())
        assertNull(call.result())
    }
}
