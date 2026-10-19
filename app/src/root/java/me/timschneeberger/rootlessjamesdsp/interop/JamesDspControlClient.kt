package me.timschneeberger.rootlessjamesdsp.interop

import android.os.IBinder
import android.os.RemoteException
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import lightning.dsp.ContextStatus
import lightning.dsp.ControlRequest
import lightning.dsp.ErrorCode
import lightning.dsp.FirEq
import lightning.dsp.IJamesDspControl
import timber.log.Timber
import java.util.concurrent.Executors

/** Optional client for the native LightningDSP control service. The legacy AudioEffect path remains primary. */
internal object JamesDspControlClient {
    private const val SERVICE_NAME = "jdsp.control"
    private const val DESCRIPTOR = "lightning.dsp.IJamesDspControl"
    private const val PROTOCOL_VERSION = IJamesDspControl.PROTOCOL_VERSION_V3

    private val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jdsp-control-probe").apply { isDaemon = true }
    }

    @Volatile private var service: IJamesDspControl? = null
    @Volatile private var soleContext: ContextStatus? = null
    @Volatile private var directWritesAvailable = false
    @Volatile private var probeInProgress = false

    /** Probe off the app's startup/audio path; lack of service access is expected and non-fatal. */
    @Synchronized
    fun probeAsync() {
        if (BuildConfig.ROOTLESS || BuildConfig.PLUGIN) return
        if (probeInProgress || directWritesAvailable) return
        probeInProgress = true
        probeExecutor.execute {
            try {
                // The HAL may publish the endpoint lazily while creating the app's
                // AudioEffect. Retry briefly, but never block startup or audio work.
                for (delayMs in longArrayOf(0, 250, 500, 1_000, 2_000)) {
                    if (delayMs > 0) Thread.sleep(delayMs)
                    if (probe()) return@execute
                }
                Timber.i("jdsp.control not visible after startup retries; keeping AudioEffect backend")
            } finally {
                synchronized(this) { probeInProgress = false }
            }
        }
    }

    @Synchronized
    private fun probe(): Boolean {
        try {
            val remoteBinder = checkServiceNative(SERVICE_NAME)
            if (remoteBinder == null) {
                clear()
                return false
            }
            if (remoteBinder.interfaceDescriptor != DESCRIPTOR) {
                clear()
                Timber.w("jdsp.control descriptor mismatch: ${remoteBinder.interfaceDescriptor}")
                return true
            }

            val remote = IJamesDspControl.Stub.asInterface(remoteBinder)
            val info = remote.getProtocolInfo(PROTOCOL_VERSION)
            if (info.result?.code != ErrorCode.OK) {
                clear()
                Timber.w("jdsp.control protocol negotiation failed: ${info.result?.code}")
                return true
            }

            val contextList = remote.listContexts(PROTOCOL_VERSION)
            val contexts = contextList.contexts ?: emptyArray()
            val oneContext = contexts.singleOrNull()
            service = remote
            soleContext = oneContext
            directWritesAvailable = info.writable && info.directAppAccessSupported &&
                info.realtimeWriteHandoffReady && !info.administrativeOnly && oneContext != null

            remoteBinder.linkToDeath({ clear() }, 0)
            Timber.i(
                "jdsp.control connected: protocol=${info.protocolVersion}, contexts=${contexts.size}, " +
                    "writable=${info.writable}, directAppAccess=${info.directAppAccessSupported}, " +
                    "realtimeReady=${info.realtimeWriteHandoffReady}, directWritesAvailable=$directWritesAvailable"
            )
            return true
        } catch (e: SecurityException) {
            clear()
            Timber.i(e, "jdsp.control discovery/call denied; keeping AudioEffect backend")
        } catch (e: RemoteException) {
            clear()
            Timber.w(e, "jdsp.control transaction failed; keeping AudioEffect backend")
        } catch (e: RuntimeException) {
            clear()
            Timber.w(e, "jdsp.control probe failed; keeping AudioEffect backend")
        }
        return false
    }

    /** Use native enable only when the service explicitly authorizes direct app writes. */
    @Synchronized
    fun setEnabledIfSupported(enabled: Boolean): Boolean = withWritableContext { remote, context ->
        remote.setEnabled(request(context), enabled).code == ErrorCode.OK
    }

    @Synchronized
    fun setPostGainIfSupported(gainDb: Float): Boolean {
        if (!gainDb.isFinite()) return false
        return withWritableContext { remote, context ->
            remote.setPostGain(request(context), gainDb).code == ErrorCode.OK
        }
    }

    @Synchronized
    fun setFirEqIfSupported(frequenciesHz: FloatArray, gainsDb: FloatArray, interpolation: Int, enabled: Boolean): Boolean {
        if (frequenciesHz.size != 15 || gainsDb.size != 15 ||
            frequenciesHz.any { !it.isFinite() || it <= 0f } || gainsDb.any { !it.isFinite() } ||
            interpolation !in 0..1
        ) return false

        return withWritableContext { remote, context ->
            val eq = FirEq().apply {
                this.frequencyHz = frequenciesHz.copyOf()
                this.gainDb = gainsDb.copyOf()
                this.interpolation = interpolation
                this.enabled = enabled
            }
            remote.setFirEq(request(context), eq).code == ErrorCode.OK
        }
    }

    private fun request(context: ContextStatus) = ControlRequest().apply {
        protocolVersion = PROTOCOL_VERSION
        handle = context.handle
        expectedAcceptedGeneration = context.acceptedGeneration
    }

    private inline fun withWritableContext(block: (IJamesDspControl, ContextStatus) -> Boolean): Boolean {
        if (!directWritesAvailable) return false
        val remote = service ?: return false
        val context = soleContext ?: return false
        return try {
            block(remote, context)
        } catch (e: RemoteException) {
            clear()
            Timber.w(e, "jdsp.control write failed; caller should use AudioEffect backend")
            false
        } catch (e: SecurityException) {
            clear()
            Timber.w(e, "jdsp.control rejected app access; caller should use AudioEffect backend")
            false
        }
    }

    private fun clear() {
        directWritesAvailable = false
        soleContext = null
        service = null
    }

    @Suppress("FunctionName")
    private external fun checkServiceNative(serviceName: String): IBinder?

    init {
        System.loadLibrary("jamesdsp-wrapper")
    }
}
