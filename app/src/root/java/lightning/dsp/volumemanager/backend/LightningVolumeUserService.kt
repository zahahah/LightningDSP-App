package lightning.dsp.volumemanager.backend

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import lightning.dsp.BuildConfig
import java.io.PrintStream

/** Private, fixed playback protocol. No arbitrary service lookup or Binder transaction relay. */
class LightningVolumeUserService : IVolumePlaybackService.Stub() {
    private val context: Context by lazy { PrivilegedVolumeContext.obtain() }
    private var commands: ParcelFileDescriptor? = null
    private var responses: ParcelFileDescriptor? = null
    private var receipt: ParcelFileDescriptor? = null
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    @Volatile private var engine: Thread? = null
    private var used = false

    private fun enforceOwner() {
        check(VolumeBridgeIdentity.privileged(android.os.Process.myUid()))
        val expected = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        val caller = Binder.getCallingUid()
        if (!VolumeBridgeIdentity.owns(caller, expected, BuildConfig.APPLICATION_ID, context.packageManager.getPackagesForUid(caller)?.toList().orEmpty()))
            throw SecurityException("Only LightningDSP may own playback control")
    }

    @Synchronized override fun start(commands: ParcelFileDescriptor, responses: ParcelFileDescriptor, receipt: ParcelFileDescriptor, nonce: String, owner: IBinder) {
        enforceOwner()
        require(VolumeCleanupReceipt.validNonce(nonce))
        check(!used) { "Playback service ownership cannot be reassigned" }
        used = true
        this.commands = commands
        this.responses = responses
        this.receipt = receipt
        this.owner = owner
        death = IBinder.DeathRecipient { closeCommunicationPipes() }.also { owner.linkToDeath(it, 0) }
        engine = Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(commands).use { input ->
                    PrintStream(ParcelFileDescriptor.AutoCloseOutputStream(responses)).use { output ->
                        ParcelFileDescriptor.AutoCloseOutputStream(receipt).use { receiptOutput ->
                            LightningDspVolumeRoot.run(input, output) { clean ->
                                VolumeCleanupReceipt.write(receiptOutput, nonce, clean) { receipt.fileDescriptor.sync() }
                            }
                        }
                    }
                }
            } finally {
                val completion = synchronized(this) { this.owner }
                closePipes()
                engine = null
                runCatching { completion?.let { IVolumePlaybackOwner.Stub.asInterface(it).stopped() } }
            }
        }, "LightningDSP-Shizuku-playback").apply { start() }
    }

    override fun isRunning(): Boolean { enforceOwner(); return engine?.isAlive == true }
    override fun stop() { enforceOwner(); closeCommandPipe() }
    override fun destroy() {
        // Shizuku invokes the reserved destroy transaction itself; only our app or the
        // privileged Shizuku server may request removal, never a different normal UID.
        val caller = Binder.getCallingUid()
        if (caller != 0 && caller != 2000) enforceOwner()
        closeCommunicationPipes()
        Thread({ engine?.join(3000); kotlin.system.exitProcess(0) }, "LightningDSP-Shizuku-destroy").start()
    }

    @Synchronized private fun closeCommandPipe() {
        runCatching { commands?.close() }; commands = null
    }

    @Synchronized private fun closeCommunicationPipes() {
        runCatching { commands?.close() }; commands = null
        runCatching { responses?.close() }; responses = null
    }

    @Synchronized private fun closePipes() {
        closeCommunicationPipes()
        runCatching { receipt?.close() }; receipt = null
        death?.let { recipient -> runCatching { owner?.unlinkToDeath(recipient, 0) } }
        death = null; owner = null
    }
}
