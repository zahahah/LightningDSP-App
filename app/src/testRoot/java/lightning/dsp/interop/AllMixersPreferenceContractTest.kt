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
        assertEquals("Cover additional sessions", value("src/main/res/values/strings.xml",
            "string", "audio_format_all_mixers"))
        assertEquals("Applies effects to supported sessions not covered by Global Mode.",
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
        assertTrue(source.contains("updateProcessingMode(preferences.get<Boolean>(R.string.key_audioformat_processing))"))
        assertTrue(source.contains("updateProcessingMode(globalEnabled)"))
        val helper = source.substringAfter("private fun updateProcessingMode(globalEnabled: Boolean)")
            .substringBefore("companion object")
        assertTrue(helper.contains("isVisible = isRoot() && globalEnabled"))
        assertTrue(helper.contains("isEnabled = isRoot() && globalEnabled"))
        assertFalse(helper.contains("isChecked"))
        assertFalse(helper.contains("preferences.set"))
        assertFalse(helper.contains("startService"))
        assertFalse(Regex("allMixers\\?*\\.isChecked\\s*=").containsMatchIn(source))
    }

    @Test fun processingModesAreNamedWithoutChangingStoredIdentity() {
        assertEquals("Global Mode", value("src/main/res/values/strings.xml",
            "string", "audio_format_processing_global"))
        assertEquals("Session Mode", value("src/main/res/values/strings.xml",
            "string", "audio_format_processing_session"))
        assertEquals("audioformat_processing", value("src/main/res/values/keys.xml",
            "string", "key_audioformat_processing"))
        val source = File(app,
            "src/main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt").readText()
        assertTrue(source.contains("globalMode?.title = getString(if (globalEnabled)"))
        assertTrue(source.contains("R.string.audio_format_processing_global else R.string.audio_format_processing_session"))
        assertTrue(source.contains("RootAudioProcessorService.updateGlobalMode"))
        assertFalse(source.contains("legacyMode"))
        val nodes = xml("src/main/res/xml/app_audio_format_preferences.xml")
            .getElementsByTagName("lightning.dsp.preference.MaterialSwitchPreference")
        val mode = (0 until nodes.length).map { nodes.item(it) as Element }.single {
            it.getAttributeNS(androidNamespace, "key") == "@string/key_audioformat_processing"
        }
        assertEquals("@string/audio_format_processing_global", mode.getAttributeNS(androidNamespace, "title"))
        assertEquals("@string/audio_format_processing_global_summary", mode.getAttributeNS(androidNamespace, "summaryOn"))
        assertEquals("@string/audio_format_processing_session_summary", mode.getAttributeNS(androidNamespace, "summaryOff"))
        assertFalse(source.contains("R.string.audio_format_processing_legacy"))
    }
}
