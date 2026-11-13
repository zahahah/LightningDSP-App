package lightning.dsp.interop.appmixer

import java.io.IOException
import java.io.InputStream

/** Drains child diagnostics without allowing expected process-close IO to crash the app. */
internal object AppMixerProcessStreamDrainer {
    fun drain(stream: InputStream, onLine: (String) -> Unit = {}) {
        try {
            stream.use {
                val bytes = ByteArray(1024)
                val line = StringBuilder(256)
                var oversized = false
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val char = (bytes[index].toInt() and 0xff).toChar()
                        if (char == '\n') {
                            if (!oversized) runCatching { onLine(line.toString()) }
                            line.setLength(0)
                            oversized = false
                        } else if (!oversized) {
                            if (line.length < 512) line.append(char)
                            else { oversized = true; line.setLength(0) }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Process termination can interrupt a blocked read on Android.
        }
    }
}
