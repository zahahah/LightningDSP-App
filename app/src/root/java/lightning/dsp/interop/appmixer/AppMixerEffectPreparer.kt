package lightning.dsp.interop.appmixer

import android.content.Context
import android.content.Intent
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import lightning.dsp.interop.LightningDspRemoteEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Called off the main thread; a positive-session engine writes the full current DSP state. */
internal class AppMixerEffectPreparer(private val context: Context, private val shouldEnable: () -> Boolean, private val localDsp: Boolean = true) {
    private companion object {
        // Applying all DSP namespaces can include FIR/file-backed effects. Keep
        // startup bounded, but do not abandon a valid session after only 7s.
        const val PREPARATION_TIMEOUT_MS = 20L * 1_000L
    }

    private fun diagnostic(engine: LightningDspRemoteEngine): String =
        "${engine.sessionPreparationStatus()} power=${runCatching(shouldEnable).getOrDefault(false)}"

    fun prepare(sessionId: Int): AutoCloseable? {
        if (sessionId <= 0) return null
        // Experimental helper completes all local DSP preparation before publishing
        // SESSION. Attaching a second AudioEffect here would duplicate processing
        // and prevent the return track from remaining low latency.
        if (localDsp) {
            if (!shouldEnable()) return null
            reportLocalSampleRate(48000f)
            return AutoCloseable { reportLocalSampleRate(0f) }
        }
        return prepareFrameworkEffect(sessionId)
    }

    private fun reportLocalSampleRate(rate: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE)
            .putExtra(Constants.EXTRA_SAMPLE_RATE, rate))
    }

    /** Retained legacy backend, not used by the experimental local-DSP helper. */
    private fun prepareFrameworkEffect(sessionId: Int): AutoCloseable? {
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
