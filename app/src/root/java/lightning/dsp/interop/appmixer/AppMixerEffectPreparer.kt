package lightning.dsp.interop.appmixer

import android.content.Context
import lightning.dsp.interop.LightningDspRemoteEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Called off the main thread; a positive-session engine writes the full current DSP state. */
internal class AppMixerEffectPreparer(private val context: Context, private val shouldEnable: () -> Boolean) {
    fun prepare(sessionId: Int): AutoCloseable? {
        if (sessionId <= 0) return null
        val engine = LightningDspRemoteEngine(context.applicationContext, sessionId, 0)
        val ready = CountDownLatch(1)
        val prepared = AtomicBoolean(false)
        try {
            engine.prepareInitialSessionState(shouldEnable = shouldEnable) {
                prepared.set(it)
                ready.countDown()
            }
            if (!ready.await(7, TimeUnit.SECONDS) || !prepared.get()) {
                engine.close()
                return null
            }
            return engine
        } catch (failure: Exception) {
            engine.close()
            throw failure
        }
    }
}
