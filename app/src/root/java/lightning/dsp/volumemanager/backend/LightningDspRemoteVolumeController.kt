package lightning.dsp.volumemanager.backend

import android.content.Context
import android.media.session.MediaSessionManager
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Optional privileged observer; never replaces SystemUI's remote callback. */
internal class LightningDspRemoteVolumeController private constructor(
    private val manager: MediaSessionManager,
    private val callbackType: Class<*>,
    private val callback: Any,
    private val active: AtomicBoolean,
) : Closeable {
    private var closed = false
    @Synchronized override fun close() {
        active.set(false)
        if (closed) return
        manager.javaClass.getMethod("unregisterRemoteSessionCallback", callbackType).invoke(manager, callback)
        closed = true
    }

    companion object {
        fun install(context: Context, bypass: () -> Boolean, onShowPanel: () -> Unit): LightningDspRemoteVolumeController {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val type = Class.forName("android.media.session.MediaSessionManager\$RemoteSessionCallback")
            val active = AtomicBoolean(true)
            val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "LightningDSP remote volume callback"
                    "onVolumeChanged" -> {
                        val flags = args?.getOrNull(1) as? Int
                        if (active.get() && flags != null && RemoteVolumeEventPolicy.shouldShow(flags,
                                runCatching(bypass).getOrDefault(true))) runCatching(onShowPanel)
                        null
                    }
                    else -> null
                }
            }
            val register = manager.javaClass.getMethod("registerRemoteSessionCallback", Executor::class.java, type)
            // Direct execution only writes the already-owned pipe; it does not create UI or change volume.
            register.invoke(manager, Executor { it.run() }, callback)
            return LightningDspRemoteVolumeController(manager, type, callback, active)
        }
    }
}
