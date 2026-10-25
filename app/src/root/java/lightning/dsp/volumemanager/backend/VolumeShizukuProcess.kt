package lightning.dsp.volumemanager.backend

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Adapts owned FD pipes to the same lifecycle contract as the root child. */
internal class VolumeShizukuStartException(message: String, val cleanupUncertain: Boolean, cause: Exception) : Exception(message, cause)

internal class VolumeShizukuProcess(context: Context, receiptFile: java.io.File, nonce: String) : Process() {
    private val commandPipe = ParcelFileDescriptor.createPipe()
    private val responsePipe = ParcelFileDescriptor.createPipe()
    private val receipt = ParcelFileDescriptor.open(receiptFile, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_APPEND)
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(commandPipe[1])
    private val input = ParcelFileDescriptor.AutoCloseInputStream(responsePipe[0])
    private val ready = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val owner = object : IVolumePlaybackOwner.Stub() {
        override fun stopped() {
            if (!VolumeBridgeIdentity.privileged(Binder.getCallingUid())) throw SecurityException("Privileged completion required")
            finished.countDown()
        }
    }
    @Volatile private var service: IVolumePlaybackService? = null
    @Volatile private var disconnected = false
    private var removed = false
    private val startLock = Any()
    @Volatile private var cancelled = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context, LightningVolumeUserService::class.java))
        .tag("lightningdsp-volume-playback-${java.util.UUID.randomUUID()}").version(1).daemon(false).processNameSuffix("lightning_volume")
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // ServiceConnection runs on the main thread; even a narrow Binder operation
            // belongs on bounded startup I/O, never on the UI callback.
            Thread({
                try {
                    val remote = IVolumePlaybackService.Stub.asInterface(binder)
                    synchronized(startLock) {
                        if (cancelled) return@Thread
                        service = remote
                    }
                    binder.linkToDeath({ finished.countDown() }, 0)
                    remote.start(commandPipe[0], responsePipe[1], receipt, nonce, owner)
                    if (cancelled) runCatching { remote.destroy() }
                } catch (_: Exception) { disconnected = true }
                finally {
                    runCatching { commandPipe[0].close() }; runCatching { responsePipe[1].close() }; runCatching { receipt.close() }
                    ready.countDown()
                    if (cancelled) runCatching { Shizuku.unbindUserService(args, connection, true) }
                }
            }, "LightningDSP-Shizuku-start").apply { isDaemon = true; start() }
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected = true; ready.countDown(); finished.countDown() }
    }
    init {
        try {
            Shizuku.bindUserService(args, connection)
            check(ready.await(15, TimeUnit.SECONDS) && !disconnected && service != null) { "Shizuku playback service did not connect" }
        } catch (failure: Exception) {
            synchronized(startLock) { cancelled = true }
            commandPipe.forEach { runCatching { it.close() } }; responsePipe.forEach { runCatching { it.close() } }; runCatching { receipt.close() }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            // This constructor cannot send activate=true: the host session is not
            // attached yet. A late service therefore cannot have changed any gains.
            throw VolumeShizukuStartException("Shizuku playback startup failed", false, failure)
        }
    }
    override fun getOutputStream(): OutputStream = output
    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun exitValue(): Int {
        if (finished.count != 0L) throw IllegalThreadStateException("Playback exit not confirmed")
        removeService()
        return 0
    }
    override fun waitFor(): Int { finished.await(); return exitValue() }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)
    override fun destroy() {
        Thread({ runCatching { service?.stop() } }, "LightningDSP-Shizuku-stop")
            .apply { isDaemon = true; start() }
    }
    private fun removeService() = synchronized(this) {
        if (!removed) {
            removed = true
            Thread({ runCatching { Shizuku.unbindUserService(args, connection, true) } },
                "LightningDSP-Shizuku-remove").apply { isDaemon = true; start() }
        }
    }
    override fun destroyForcibly(): Process { removeService(); return this }
}
