package lightning.dsp.interop

import lightning.dsp.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetPreferenceSyncPolicyTest {
    @Test
    fun presetReloadForcesEveryDspNamespace() {
        assertEquals(
            Constants.DSP_PREFERENCE_NAMESPACES.toSet(),
            PresetPreferenceSyncPolicy
                .forceUpdateNamespacesFor(Constants.ACTION_PRESET_LOADED)
                ?.toSet(),
        )
    }

    @Test
    fun presetReloadUsesNoDuplicateNamespaces() {
        val namespaces = PresetPreferenceSyncPolicy
            .forceUpdateNamespacesFor(Constants.ACTION_PRESET_LOADED)!!
        assertEquals(namespaces.size, namespaces.toSet().size)
    }

    @Test
    fun ordinaryPreferenceUpdateKeepsIncrementalSync() {
        assertNull(PresetPreferenceSyncPolicy.forceUpdateNamespacesFor(Constants.ACTION_PREFERENCES_UPDATED))
    }
}
