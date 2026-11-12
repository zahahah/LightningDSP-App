package lightning.dsp.interop.appmixer

import java.io.IOException
import java.io.InputStream

/** Drains child diagnostics without allowing expected process-close IO to crash the app. */
internal object AppMixerProcessStreamDrainer {
    fun drain(stream: InputStream) {
        try {
            stream.use {
                val bytes = ByteArray(1024)
                while (it.read(bytes) >= 0) { /* discard helper stderr */ }
            }
        } catch (_: IOException) {
            // Process termination can interrupt a blocked read on Android.
        }
    }
}
