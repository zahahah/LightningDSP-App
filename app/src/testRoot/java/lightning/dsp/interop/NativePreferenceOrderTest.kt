package lightning.dsp.interop

import lightning.dsp.utils.Constants
import org.junit.Assert.*
import org.junit.Test

class NativePreferenceOrderTest {
    @Test fun idleNativeFirIsNotCancelledByLaterLegacyWritesInTheSameSync() {
        val input = arrayOf(Constants.PREF_EQ, "legacy-a", Constants.PREF_OUTPUT, "legacy-b", Constants.PREF_EQ)
        assertArrayEquals(arrayOf("legacy-a", "legacy-b", Constants.PREF_OUTPUT, Constants.PREF_EQ),
            orderNativePreferenceUpdates(input, true))
        assertArrayEquals(input, orderNativePreferenceUpdates(input, false))
    }
}
