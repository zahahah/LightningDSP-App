package lightning.dsp.interop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Resource/wiring contracts; actual availability and playback need device validation. */
class AllMixersPreferenceContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(app, path))

    private fun value(path: String, tag: String, name: String): String {
        val nodes = xml(path).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == name }.textContent
    }

    @Test fun settingHasApprovedTextAndDefaultWithoutAnInvertedDependency() {
        assertEquals("audioformat_all_mixers", value("src/main/res/values/keys.xml",
            "string", "key_audioformat_all_mixers"))
        assertEquals("true", value("src/main/res/values/defaults.xml",
            "bool", "default_audioformat_all_mixers"))
        assertEquals("Apply effects to all mixers", value("src/main/res/values/strings.xml",
            "string", "audio_format_all_mixers"))
        assertEquals("Applies effects to all supported audio mixers in Global mode.",
            value("src/main/res/values/strings.xml", "string", "audio_format_all_mixers_summary"))

        val nodes = xml("src/main/res/xml/app_audio_format_preferences.xml")
            .getElementsByTagName("lightning.dsp.preference.MaterialSwitchPreference")
        val setting = (0 until nodes.length).map { nodes.item(it) as Element }.single {
            it.getAttributeNS(androidNamespace, "key") == "@string/key_audioformat_all_mixers"
        }
        assertEquals("@bool/default_audioformat_all_mixers",
            setting.getAttributeNS(androidNamespace, "defaultValue"))
        assertEquals("@string/audio_format_all_mixers", setting.getAttributeNS(androidNamespace, "title"))
        assertEquals("@string/audio_format_all_mixers_summary", setting.getAttributeNS(androidNamespace, "summary"))
        // Global's disableDependentsState=true is deliberately inverted for scanning.
        // It cannot be reused for a setting that is enabled when Global is ON.
        assertFalse(setting.hasAttributeNS(androidNamespace, "dependency"))
        assertFalse(setting.hasAttributeNS(androidNamespace, "disableDependentsState"))
        assertNotEquals("false", setting.getAttributeNS(androidNamespace, "persistent"))
    }

    @Test fun rootAvailabilityTracksGlobalWithoutOverwritingTheSavedChoice() {
        val source = File(app,
            "src/main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt").readText()
        assertTrue(source.contains("updateAllMixersAvailability(preferences.get<Boolean>(R.string.key_audioformat_processing))"))
        assertTrue(source.contains("updateAllMixersAvailability(globalMode)"))
        val helper = source.substringAfter("private fun updateAllMixersAvailability(globalMode: Boolean)")
            .substringBefore("companion object")
        assertTrue(helper.contains("isVisible = isRoot()"))
        assertTrue(helper.contains("isEnabled = isRoot() && globalMode"))
        assertFalse(helper.contains("isChecked"))
        assertFalse(helper.contains("preferences.set"))
        assertFalse(helper.contains("startService"))
        assertFalse(Regex("allMixers\\?*\\.isChecked\\s*=").containsMatchIn(source))
    }
}
