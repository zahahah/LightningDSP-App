package lightning.dsp.model.preset

import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import lightning.dsp.interop.DspDesiredState
import lightning.dsp.utils.Constants
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedPreferencesSnapshotTest {
    @get:Rule val folder = TemporaryFolder()

    private class MemoryPreferences(initial: Map<String, Any?>, var failNextCommit: Boolean = false) {
        val values = initial.toMutableMap()
        private var staged = mutableMapOf<String, Any?>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "clear" -> { staged.clear(); proxy }
                "remove" -> { staged.remove(args!![0] as String); proxy }
                "putString", "putBoolean", "putInt", "putLong", "putFloat", "putStringSet" -> {
                    staged[args!![0] as String] = args[1]
                    proxy
                }
                "commit" -> {
                    // Android commit can update memory even when the disk write reports failure.
                    values.clear(); values.putAll(staged)
                    (!failNextCommit).also { failNextCommit = false }
                }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> { staged = values.toMutableMap(); editor }
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun snapshotUsesLiveValuesInsteadOfStaleDiskXml() {
        val disk = folder.newFile("dsp_bass.xml").apply {
            writeText("<map><boolean name='enabled' value='false'/></map>")
        }
        val loaded = MemoryPreferences(mapOf("enabled" to true, "gain" to 7.5f))
        val bytes = ByteArrayOutputStream()
        SharedPreferencesXml.write(loaded.preferences, bytes)
        assertEquals(listOf(SharedPreferencesXml.Value.BooleanValue("enabled", true),
            SharedPreferencesXml.Value.FloatValue("gain", 7.5f)), SharedPreferencesXml.parse(bytes.toByteArray().inputStream()))
        assertTrue(disk.readText().contains("false"))
    }

    @Test fun loadedPreferencesWithoutXmlStillRoundTripAllSupportedTypes() {
        val loaded = MemoryPreferences(linkedMapOf("text" to "speaker & <headphones> \"' 🎵",
            "enabled" to false, "int" to 3, "long" to 1234567890123L, "float" to -3.25f,
            "set" to setOf("left & right", "<both>")))
        val bytes = ByteArrayOutputStream()
        SharedPreferencesXml.write(loaded.preferences, bytes)
        val restored = MemoryPreferences(emptyMap())
        SharedPreferencesXml.replaceAll(mapOf("dsp_bass" to SharedPreferencesXml.parse(bytes.toByteArray().inputStream())),
            preferencesFor = { restored.preferences })
        assertEquals(loaded.values, restored.values)
    }

    @Test fun emptyLoadedNamespaceProducesValidDefaults() {
        val bytes = ByteArrayOutputStream()
        SharedPreferencesXml.write(MemoryPreferences(emptyMap()).preferences, bytes)
        assertTrue(SharedPreferencesXml.parse(bytes.toByteArray().inputStream()).isEmpty())
    }

    @Test fun partialOlderPresetDisablesEffectsMissingFromItsArchive() {
        val bass = MemoryPreferences(mapOf("enabled" to true))
        val reverb = MemoryPreferences(mapOf("enabled" to true))
        val preferences = mutableMapOf(Constants.PREF_BASS to bass, Constants.PREF_REVERB to reverb)
        val incoming = SharedPreferencesXml.completeSnapshot(mapOf(Constants.PREF_REVERB to
            listOf(SharedPreferencesXml.Value.BooleanValue("enabled", false))))
        SharedPreferencesXml.replaceAll(incoming, preferencesFor = {
            preferences.getOrPut(it) { MemoryPreferences(emptyMap()) }.preferences
        })
        assertFalse(bass.values["enabled"] as? Boolean ?: false)
        assertEquals(false, reverb.values["enabled"])
    }

    @Test fun failedLaterCommitRestoresAllPreviousLiveNamespaces() {
        val bass = MemoryPreferences(mapOf("enabled" to true))
        val reverb = MemoryPreferences(mapOf("enabled" to true), failNextCommit = true)
        val preferences = mapOf("dsp_bass" to bass, "dsp_reverb" to reverb)
        try {
            SharedPreferencesXml.replaceAll(linkedMapOf("dsp_bass" to emptyList(), "dsp_reverb" to emptyList()),
                preferencesFor = { preferences.getValue(it).preferences })
            fail("Failed disk commit must not report a loaded preset")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("dsp_reverb"))
        }
        assertEquals(mapOf("enabled" to true), bass.values)
        assertEquals(mapOf("enabled" to true), reverb.values)
    }

    @Test fun failedFollowUpRestoresPreferencesAndInvalidatesQueuedOldGeneration() {
        val loaded = MemoryPreferences(mapOf("enabled" to true))
        val oldGeneration = DspDesiredState.current()
        try {
            DspDesiredState.replace {
                SharedPreferencesXml.replaceAll(mapOf("dsp_bass" to emptyList()),
                    preferencesFor = { loaded.preferences },
                    afterApply = { throw IllegalStateException("Liveprog unavailable") })
            }
            fail("Follow-up failure must not report success")
        } catch (_: IllegalStateException) { }
        DspDesiredState.applyIfCurrent(oldGeneration) { loaded.values["enabled"] = false }
        assertEquals(mapOf("enabled" to true), loaded.values)
    }
    @Test(expected = IllegalArgumentException::class)
    fun snapshotRejectsNonFiniteNumbersInsteadOfSavingAnUnusableProfile() {
        SharedPreferencesXml.write(MemoryPreferences(mapOf("gain" to Float.NaN)).preferences,
            ByteArrayOutputStream())
    }

}
