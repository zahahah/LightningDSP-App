package lightning.dsp.interop

import lightning.dsp.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetPreferenceSyncPolicyTest {
    @Test
    fun presetReloadForcesEveryDspNamespace() {
        assertEquals(
            setOf(
                Constants.PREF_OUTPUT,
                Constants.PREF_COMPANDER,
                Constants.PREF_BASS,
                Constants.PREF_EQ,
                Constants.PREF_GEQ,
                Constants.PREF_REVERB,
                Constants.PREF_STEREOWIDE,
                Constants.PREF_CROSSFEED,
                Constants.PREF_TUBE,
                Constants.PREF_DDC,
                Constants.PREF_LIVEPROG,
                Constants.PREF_CONVOLVER,
            ),
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
