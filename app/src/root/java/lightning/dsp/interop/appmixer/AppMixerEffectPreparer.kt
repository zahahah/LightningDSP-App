package lightning.dsp.interop.appmixer

import android.content.Context
import lightning.dsp.interop.LightningDspRemoteEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Called off the main thread; a positive-session engine writes the full current DSP state. */
internal class AppMixerEffectPreparer(private val context: Context, private val shouldEnable: () -> Boolean) {
    private companion object {
        // Applying all DSP namespaces can include FIR/file-backed effects. Keep
        // startup bounded, but do not abandon a valid session after only 7s.
        const val PREPARATION_TIMEOUT_MS = 20L * 1_000L
    }

    private fun diagnostic(engine: LightningDspRemoteEngine): String =
        "${engine.sessionPreparationStatus()} power=${runCatching(shouldEnable).getOrDefault(false)}"

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
            if (!ready.await(PREPARATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                val status = diagnostic(engine)
                engine.close()
                throw IllegalStateException("DSP state preparation timed out after ${PREPARATION_TIMEOUT_MS / 1_000}s ($status)")
            }
            if (!prepared.get()) {
                val status = diagnostic(engine)
                engine.close()
                throw IllegalStateException("DSP state preparation was rejected ($status)")
            }
            return engine
        } catch (failure: Exception) {
            engine.close()
            throw failure
        }
    }
}
