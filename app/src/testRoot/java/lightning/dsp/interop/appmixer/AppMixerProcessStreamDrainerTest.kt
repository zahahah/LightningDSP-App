package lightning.dsp.interop.appmixer

import java.io.InterruptedIOException
import java.io.InputStream
import org.junit.Test

class AppMixerProcessStreamDrainerTest {
    @Test fun interruptedPipeReadDoesNotEscapeWorkerThread() {
        val interrupted = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("read interrupted by close")
        }
        AppMixerProcessStreamDrainer.drain(interrupted)
    }
}
