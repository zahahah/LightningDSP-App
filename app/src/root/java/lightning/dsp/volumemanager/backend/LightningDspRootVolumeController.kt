package lightning.dsp.volumemanager.backend

import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.io.Closeable
import java.lang.reflect.Method

/** Owns the volume-key panel only while the isolated root bridge is explicitly enabled. */
class LightningDspRootVolumeController private constructor(
    private val audio: AudioManager,
    private val getController: Method,
    private val setController: Method,
    private val previous: Any?,
    descriptor: String,
    volumeChangedTransaction: Int,
    shouldBypassPanel: () -> Boolean,
    onShowPanel: () -> Unit
) : Closeable {
    private val previousBinder = (previous as? IInterface)?.asBinder()
    private var closed = false
    private val callback = object : Binder() {
        init {
            // Keep queryLocalInterface null: the framework Stub creates its own platform proxy.
            attachInterface(null, descriptor)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code == volumeChangedTransaction) {
                data.enforceInterface(descriptor)
                val stream = data.readInt()
                val volumeFlags = data.readInt()
                val showUi = volumeFlags and AudioManager.FLAG_SHOW_UI != 0
                // Read current per-app policy for each key event; never cache an old foreground app.
                // Policy failure leaves the platform panel available instead of swallowing the key.
                val bypass = showUi && runCatching { shouldBypassPanel() }.getOrDefault(true)
                if (showUi && !bypass) {
                    // Binder-thread callbacks must only enqueue/emit a host event, not create UI.
                    // Host failure must not prevent forwarding native controller bookkeeping.
                    runCatching { onShowPanel() }
                }
                val forwarded = Parcel.obtain()
                return try {
                    forwarded.writeInterfaceToken(descriptor)
                    forwarded.writeInt(stream)
                    forwarded.writeInt(if (bypass) volumeFlags else volumeFlags and AudioManager.FLAG_SHOW_UI.inv())
                    previousBinder?.transact(code, forwarded, reply, flags)
                    true
                } finally {
                    forwarded.recycle()
                }
            }
            // In particular, preserve safe-volume and sound-dose warnings in SystemUI.
            // Unknown vendor callbacks are forwarded without guessing their parcel layout.
            if (previousBinder != null) {
                data.setDataPosition(0)
                return previousBinder.transact(code, data, reply, flags)
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /** Do not overwrite a controller installed by SystemUI or another owner after ours. */
    @Synchronized
    override fun close() {
        if (closed) return
        val current = getController.invoke(audio) as? IInterface
        // setVolumeController resets framework visibility when restoring the previous owner.
        // Never notify using another owner's binder or undo a newer controller's visibility.
        if (current?.asBinder() == callback) setController.invoke(audio, previous)
        closed = true
    }

    companion object {
        /** Fails closed on firmware without a readable controller or matching platform Stub. */
        fun install(
            audio: AudioManager,
            shouldBypassPanel: () -> Boolean = { false },
            onShowPanel: () -> Unit
        ): LightningDspRootVolumeController {
            check(android.os.Process.myUid() == 0) { "Root is required for the volume panel" }
            val contract = Class.forName("android.media.IVolumeController")
            val stub = Class.forName("android.media.IVolumeController\$Stub")
            val get = AudioManager::class.java.getDeclaredMethod("getVolumeController").apply { isAccessible = true }
            val set = AudioManager::class.java.getDeclaredMethod("setVolumeController", contract).apply { isAccessible = true }
            // AIDL generators have placed this constant on either the Stub or its interface.
            val descriptorField = try {
                stub.getDeclaredField("DESCRIPTOR")
            } catch (_: NoSuchFieldException) {
                contract.getDeclaredField("DESCRIPTOR")
            }
            val descriptor = descriptorField.apply { isAccessible = true }.get(null) as String
            val transaction = stub.getDeclaredField("TRANSACTION_volumeChanged").apply { isAccessible = true }.getInt(null)
            // Resolve the complete contract before replacing SystemUI. If a vendor lacks it,
            // leave the platform controller installed rather than swallowing volume keys.
            val notifyVisible = AudioManager::class.java.getDeclaredMethod(
                "notifyVolumeControllerVisible", contract, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val previous = get.invoke(audio)
            val controller = LightningDspRootVolumeController(
                audio, get, set, previous, descriptor, transaction, shouldBypassPanel, onShowPanel
            )
            val proxy = stub.getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }.invoke(null, controller.callback)
            try {
                set.invoke(audio, proxy)
                check((get.invoke(audio) as? IInterface)?.asBinder() == controller.callback) {
                    "Volume controller registration was not retained"
                }
                // AudioService suppresses default-stream adjustments while its controller is
                // invisible (except during playback). This replacement accepts volume changes
                // immediately, even when its transient panel is hidden: keep it adjustment-ready
                // for the ownership lifetime, not the overlay animation lifetime. Only Android
                // performs the hardware-key step; volumeChanged must never replay an increment.
                // Old SystemUI visibility calls use its old binder and cannot clear this state.
                notifyVisible.invoke(audio, proxy, true)
            } catch (error: Exception) {
                runCatching { controller.close() }
                throw error
            }
            return controller
        }
    }
}
