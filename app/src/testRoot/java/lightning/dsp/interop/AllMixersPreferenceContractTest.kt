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

    @Test fun audioCompatibilityIsOptInAndFollowsMediaScanning() {
        assertEquals("false", value("src/main/res/values/defaults.xml", "bool", "default_audioformat_aaudio_compat"))
        assertEquals("false", value("src/main/res/values/defaults.xml", "bool", "default_audioformat_aaudio_reuse_session"))
        assertEquals("Improve app compatibility", value("src/main/res/values/strings.xml", "string", "audio_format_aaudio_compat"))
        assertEquals("Requires selecting incompatible apps in Vector or LSPosed.",
            value("src/main/res/values/strings.xml", "string", "audio_format_aaudio_compat_summary"))
        val nodes = xml("src/main/res/xml/app_audio_format_preferences.xml")
            .getElementsByTagName("lightning.dsp.preference.MaterialSwitchPreference")
        val settings = (0 until nodes.length).map { nodes.item(it) as Element }
        val scanning = settings.indexOfFirst { it.getAttributeNS(androidNamespace, "key") == "@string/key_audioformat_enhanced_processing" }
        val compat = settings.indexOfFirst { it.getAttributeNS(androidNamespace, "key") == "@string/key_audioformat_aaudio_compat" }
        val reuse = settings.indexOfFirst { it.getAttributeNS(androidNamespace, "key") == "@string/key_audioformat_aaudio_reuse_session" }
        assertEquals(scanning + 1, compat)
        assertEquals(compat + 1, reuse)
        assertEquals("false", settings[compat].getAttributeNS(androidNamespace, "persistent"))
        assertEquals("false", settings[reuse].getAttributeNS(androidNamespace, "persistent"))
        assertEquals("Reuse AAudio session", value("src/main/res/values/strings.xml", "string", "audio_format_aaudio_reuse_session"))
        val nativeSource = File(app, "src/main/cpp/aaudio-compat/aaudio_compat.cpp").readText()
        assertTrue(nativeSource.contains("debug.ldsp.aaudio_reuse_session"))
        assertTrue(nativeSource.contains("firstPinnedSession"))
        val option = File(app, "src/root/java/lightning/dsp/interop/AAudioSessionReuseOption.kt").readText()
        assertTrue(option.contains("AAudioCompatibilityOption.requested(context)"))
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()
        // Vector calls native_init under its registry lock; reentering dlopen deadlocks.
        assertFalse(nativeSource.contains("dlopen(\"libaaudio.so\""))
        assertTrue(cmake.contains("target_link_libraries(ldsp_aaudio_compat PRIVATE log aaudio OpenSLES dl)"))
    }

    @Test fun mediaScanningDefaultsOnAndIsVisibleOnlyInSessionMode() {
        assertEquals("true", value("src/main/res/values/defaults.xml", "bool",
            "default_audioformat_enhanced_processing"))
        val source = File(app,
            "src/main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt").readText()
        assertTrue(source.contains("enhancedMode?.isVisible = isRoot() && !globalEnabled"))
        assertTrue(source.contains("val permission = requireContext().hasDumpPermission()"))
        assertTrue(source.contains("isPersistent = false"))
        val application = File(app, "src/main/java/lightning/dsp/MainApplication.kt").readText()
        assertTrue(application.contains("&& hasDumpPermission()"))
        val service = File(app, "src/main/java/lightning/dsp/service/RootAudioProcessorService.kt").readText()
        val modeChange = service.substringAfter("getString(R.string.key_audioformat_processing) -> {")
            .substringBefore("getString(R.string.key_audioformat_all_mixers)")
        assertTrue(modeChange.contains("if (!app.isGlobalMode)"))
        assertTrue(modeChange.contains("app.rootSessionDatabase.removeSession(0)"))
        assertTrue(modeChange.contains("setupEnhancedProcessing()"))
        assertTrue(modeChange.contains("sessionDumpManager?.destroy()"))
        assertTrue(modeChange.contains("if (app.isGlobalMode)"))
        assertTrue(modeChange.contains("sessionList.keys.filter { it > 0 }"))
        assertTrue(modeChange.contains("forEach(app.rootSessionDatabase::removeSession)"))
    }

    @Test fun settingHasApprovedTextAndDefaultWithoutAnInvertedDependency() {
        assertEquals("audioformat_all_mixers", value("src/main/res/values/keys.xml",
            "string", "key_audioformat_all_mixers"))
        assertEquals("true", value("src/main/res/values/defaults.xml",
            "bool", "default_audioformat_all_mixers"))
        assertEquals("Cover additional sessions", value("src/main/res/values/strings.xml",
            "string", "audio_format_all_mixers"))
        assertEquals("Covers supported audio sessions not already handled by LightningDSP.",
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
        assertTrue(helper.contains("isVisible = isRoot()"))
        assertTrue(helper.contains("isEnabled = isRoot()"))
        val coverageSetting = helper.substringAfter("allMixers?.apply {").substringBefore("\n        }")
        assertFalse(coverageSetting.contains("isChecked"))
        assertFalse(coverageSetting.contains("preferences.set"))
        assertFalse(coverageSetting.contains("startService"))
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
        assertTrue(source.contains("setProcessingMode(newValue as Boolean)"))
        assertTrue(source.contains("setProcessingMode(!(newValue as Boolean))"))
        assertTrue(source.contains("globalMode?.isChecked = globalEnabled"))
        assertTrue(source.contains("sessionMode?.isChecked = !globalEnabled"))
        assertTrue(source.contains("preferences.set(R.string.key_audioformat_processing, globalEnabled)"))
        assertTrue(source.contains("RootAudioProcessorService.updateGlobalMode"))
        assertFalse(source.contains("legacyMode"))
        val nodes = xml("src/main/res/xml/app_audio_format_preferences.xml")
            .getElementsByTagName("lightning.dsp.preference.MaterialSwitchPreference")
        val settings = (0 until nodes.length).map { nodes.item(it) as Element }
        val globalMode = settings.single {
            it.getAttributeNS(androidNamespace, "key") == "@string/key_audioformat_processing_global_option"
        }
        val sessionMode = settings.single {
            it.getAttributeNS(androidNamespace, "key") == "@string/key_audioformat_processing_session_option"
        }
        assertEquals("@string/audio_format_processing_global", globalMode.getAttributeNS(androidNamespace, "title"))
        assertEquals("@string/audio_format_processing_global_summary", globalMode.getAttributeNS(androidNamespace, "summary"))
        assertEquals("@string/audio_format_processing_session", sessionMode.getAttributeNS(androidNamespace, "title"))
        assertEquals("@string/audio_format_processing_session_summary", sessionMode.getAttributeNS(androidNamespace, "summary"))
        assertEquals("false", globalMode.getAttributeNS(androidNamespace, "persistent"))
        assertEquals("false", sessionMode.getAttributeNS(androidNamespace, "persistent"))
        assertFalse(globalMode.hasAttributeNS(androidNamespace, "defaultValue"))
        assertFalse(sessionMode.hasAttributeNS(androidNamespace, "defaultValue"))
        assertEquals("true", value("src/main/res/values/defaults.xml", "bool", "default_audioformat_processing"))
        assertFalse(source.contains("R.string.audio_format_processing_legacy"))
    }
}
