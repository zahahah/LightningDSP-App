package lightning.dsp.volumemanager.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisableAudioFocusTargetTest {
    @Test fun acceptsAospAndVectorFrameworkNamesOnly() {
        assertTrue(DisableAudioFocusTarget.isFrameworkPackage("android"))
        assertTrue(DisableAudioFocusTarget.isFrameworkPackage("system"))
        assertFalse(DisableAudioFocusTarget.isFrameworkPackage("com.lightning.dsp"))
        assertFalse(DisableAudioFocusTarget.isFrameworkPackage("com.android.systemui"))
    }
}
