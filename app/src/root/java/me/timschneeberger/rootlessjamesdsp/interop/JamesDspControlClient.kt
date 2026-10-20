package me.timschneeberger.rootlessjamesdsp.interop

import android.os.Looper
import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl
import timber.log.Timber
import java.util.concurrent.Executors

/** Global jdsp.control client. UID 0 and the vendor-native binding stay in the
 * module helper; the ordinary app never uses guessed Binder codes or Java AIDL
 * to disguise the native service's vendor-local stability. */
internal object JamesDspControlClient {
    private const val HELPER = "/vendor/bin/jdspctl"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jdsp-global-control").apply { isDaemon = true }
    }
    @Volatile private var rootAvailable = false
    @Volatile private var ready = false
    @Volatile private var probing = false
    private val probeCallbacks = mutableListOf<(Boolean) -> Unit>()

    fun isAvailable() = ready

    fun probeAsync(onComplete: ((Boolean) -> Unit)? = null) {
        synchronized(this) {
            if (ready) {
                onComplete?.let { executor.execute { it(true) } }
                return
            }
            onComplete?.let(probeCallbacks::add)
            if (probing) return
            probing = true
        }
        executor.execute {
            var result = false
            try {
                rootAvailable = RootShellImpl.hasRootShell()
                if (!rootAvailable) {
                    Timber.i("jdsp.control: root unavailable; legacy fallback")
                } else {
                    for (delayMs in longArrayOf(0, 250, 500, 1_000, 2_000)) {
                        if (delayMs > 0) Thread.sleep(delayMs)
                        val response = runHelper("probe")
                        ready = response?.ready == true
                        if (ready) {
                            Timber.i("jdsp.control: global native backend ready; ${response?.raw}")
                            break
                        }
                    }
                }
                result = ready
                if (!result) Timber.i("jdsp.control: discovery/authorization/context gate unavailable; legacy fallback")
            } catch (e: Exception) {
                Timber.w(e, "jdsp.control: probe failed; legacy fallback")
            } finally {
                val callbacks = synchronized(this) {
                    probing = false
                    probeCallbacks.toList().also { probeCallbacks.clear() }
                }
                callbacks.forEach { callback ->
                    try { callback(result) }
                    catch (e: Exception) { Timber.w(e, "jdsp.control: availability callback failed") }
                }
            }
        }
    }

    fun setEnabledIfSupported(enabled: Boolean): Boolean {
        if (!ready) { probeAsync(); return false }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // The Android effect is enabled by the engine separately. Never
            // freeze the UI while the root helper waits for HAL acknowledgement.
            executor.execute { write("enabled", enabled.toString()) }
            return false
        }
        return write("enabled", enabled.toString())
    }

    fun setPostGainIfSupported(gainDb: Float): Boolean =
        gainDb.isFinite() && gainDb in -15f..15f && write("post-gain", gainDb.toString())

    fun setFirEqIfSupported(frequenciesHz: FloatArray, gainsDb: FloatArray,
                            interpolation: Int, enabled: Boolean): Boolean {
        if (frequenciesHz.size != 15 || gainsDb.size != 15 || interpolation !in 0..1 ||
            frequenciesHz.any { !it.isFinite() || it <= 0f } ||
            gainsDb.any { !it.isFinite() || it !in -64f..64f }) return false
        return write("fir", enabled.toString(), interpolation.toString(),
            frequenciesHz.joinToString(","), gainsDb.joinToString(","))
    }

    private fun write(action: String, vararg args: String): Boolean {
        if (!ready) { probeAsync(); return false }
        val response = runHelper(action, *args) ?: return false
        Timber.i("jdsp.control: $action accepted=${response.accepted} applied=${response.applied}; ${response.raw}")
        // Pending is accepted, not applied. Do not overwrite an accepted queued
        // native request with the same legacy control just because audio is idle.
        return response.accepted
    }

    private fun runHelper(action: String, vararg args: String): NativeControlReply? {
        if (!rootAvailable || Looper.myLooper() == Looper.getMainLooper()) return null
        return try {
            val command = (listOf(HELPER, action) + args).joinToString(" ", transform = ::shellQuote)
            val reply = RootShellImpl.cmdForOutput(command).asReversed()
                ?.firstNotNullOfOrNull { NativeControlReply.parse(it) }
                ?.also {
                    if (!it.ok) {
                        Timber.w("jdsp.control: ${it.raw}")
                        if (it.raw.contains("service-unavailable") || it.raw.contains("-transport")) {
                            ready = false
                            probeAsync()
                        }
                    }
                }
            if (reply == null) { ready = false; probeAsync() }
            reply
        } catch (e: Exception) {
            Timber.w(e, "jdsp.control: native helper failed; legacy fallback")
            null
        }
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
