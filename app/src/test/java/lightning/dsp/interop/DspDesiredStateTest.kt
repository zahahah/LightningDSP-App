package lightning.dsp.interop

import org.junit.Assert.*
import org.junit.Test

class DspDesiredStateTest {
    @Test fun oldProfileCannotWriteAfterReplacement() {
        val old = DspDesiredState.current()
        var value = "speaker"
        DspDesiredState.replace { value = "earbuds" }
        DspDesiredState.applyIfCurrent(old) { value = "obsolete" }
        assertEquals("earbuds", value)
        DspDesiredState.applyIfCurrent(DspDesiredState.current()) { value = "current" }
        assertEquals("current", value)
    }
}
