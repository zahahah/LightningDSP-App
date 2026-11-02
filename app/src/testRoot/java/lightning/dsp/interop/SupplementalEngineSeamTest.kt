package lightning.dsp.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Execute the real engine's inert branches without constructing Android's
 * unavailable host AudioEffect. Allocation deliberately skips every constructor:
 * touching Context, cache, worker or endpoint in these branches fails this test.
 * This is not a device AudioEffect/control/IR integration test. */
class SupplementalEngineSeamTest {
    private fun deferredEngine(): LightningDspRemoteEngine {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val engine = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, LightningDspRemoteEngine::class.java) as LightningDspRemoteEngine
        LightningDspRemoteEngine::class.java.getDeclaredField("supplemental")
            .apply { isAccessible = true }.setBoolean(engine, true)
        LightningDspRemoteEngine::class.java.getDeclaredField("supplementalState")
            .apply { isAccessible = true }.set(engine, SupplementalStateFence(
                canUseEndpoint = { false }, isEnabled = { false },
                writeCompleteState = { false }, enable = { error("Deferred engine enabled") },
            ))
        LightningDspBaseEngine::class.java.getDeclaredField("publishSampleRate")
            .apply { isAccessible = true }.setBoolean(engine, false)
        return engine
    }

    @Test fun deferredEngineRejectsAutomaticSyncAndEnableBeforeAdmission() {
        val engine = deferredEngine()
        engine.syncWithPreferences()
        engine.enabled = true
        assertFalse(engine.enabled)
        LightningDspRemoteEngine::class.java.getDeclaredMethod("rebootEngine")
            .apply { isAccessible = true }.invoke(engine)
        assertFalse(engine.enabled)
    }

    @Test fun realSupplementalRateReadAndCloseReportNeverTouchGlobalBroadcastContext() {
        val engine = deferredEngine()
        assertEquals(0f, engine.sampleRate, 0f)
        // Base.close reports exactly this zero through the same private method.
        // No Context exists: a global broadcast would fail rather than pass.
        LightningDspBaseEngine::class.java.getDeclaredMethod("reportSampleRate", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(engine, 0f)
    }
}
