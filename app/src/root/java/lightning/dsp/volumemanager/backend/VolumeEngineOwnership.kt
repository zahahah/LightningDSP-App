package lightning.dsp.volumemanager.backend

import java.io.Closeable

/** Hold exclusive process ownership across both processing and cleanup. */
internal class VolumeEngineOwnership(private val acquire: () -> Closeable) : Closeable {
    private var lease: Closeable? = null
    fun acquire() {
        check(lease == null)
        lease = acquire.invoke()
    }
    override fun close() { lease?.close(); lease = null }
}
