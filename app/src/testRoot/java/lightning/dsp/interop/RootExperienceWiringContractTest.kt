package lightning.dsp.interop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/resource contracts; launching and rendering the shortcut require Android. */
class RootExperienceWiringContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private fun source(path: String) = File(app, path).readText()

    @Test fun rootDefaultsAreSeededBeforeServicesOrProfilesConsumeThem() {
        val application = source("src/main/java/lightning/dsp/MainApplication.kt")
        val seed = application.indexOf("seed(Constants.PREF_APP,")
        assertTrue(seed >= 0)
        assertTrue(seed < application.indexOf("profileManager = ProfileManager()"))
        assertTrue(application.contains("seed(\"lightningdsp_volume_manager\","))
        assertTrue(application.contains("seed(\"lightningdsp_volume_panel\","))
        assertTrue(application.substringBefore("fun seed(name:").trimEnd().endsWith("if (isRoot()) {"))
    }

    @Test fun audioProcessingShortcutUsesToolbarNavigationAndDedicatedSettingsDestination() {
        val main = source("src/main/java/lightning/dsp/activity/MainActivity.kt")
        assertTrue(main.contains("binding.toolbar.setNavigationIcon(R.drawable.ic_audio_processing)"))
        assertTrue(main.contains("binding.toolbar.navigationContentDescription = getString(R.string.audio_format_header)"))
        assertTrue(main.contains(".putExtra(SettingsActivity.EXTRA_OPEN_AUDIO_PROCESSING, true)"))
        val settings = source("src/main/java/lightning/dsp/activity/SettingsActivity.kt")
        assertTrue(settings.contains("intent.getBooleanExtra(EXTRA_OPEN_AUDIO_PROCESSING, false)"))
        assertTrue(settings.contains("SettingsAudioFormatFragment() else SettingsFragment.newInstance()"))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val drawable = factory.newDocumentBuilder().parse(File(app, "src/main/res/drawable/ic_audio_processing.xml"))
        val icon = drawable.documentElement
        assertEquals("vector", icon.tagName)
        assertTrue(drawable.getElementsByTagName("path").length > 0)
        assertEquals(0, drawable.getElementsByTagName("bitmap").length)
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("?attr/colorControlNormal", icon.getAttributeNS(ns, "tint"))
        assertEquals("31.48dp", icon.getAttributeNS(ns, "width"))
        assertEquals("26.496dp", icon.getAttributeNS(ns, "height"))
        val svg = File(app, "../artwork/icons/audio_processing.svg").readText()
        assertTrue(svg.contains("<path"))
        assertFalse(svg.contains("<image"))
        val preferences = factory.newDocumentBuilder().parse(File(app, "src/main/res/xml/app_preferences.xml"))
        val nodes = preferences.getElementsByTagName("Preference")
        val appNs = "http://schemas.android.com/apk/res-auto"
        val audio = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(appNs, "key") == "@string/key_audio_format" }
        assertEquals("@drawable/ic_audio_processing", audio.getAttributeNS(appNs, "icon"))
    }

    @Test fun defaultMediaControlsUseOneGuardedGrantOnTheExistingWorker() {
        val backend = source("src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeManager.kt")
        assertTrue(backend.contains("worker.execute { configureMediaControls() }"))
        assertTrue(backend.contains("showMediaControls && !hasNotificationAccess && !mediaAccessGrantAttempted"))
        assertTrue(backend.contains("mediaAccessGrantAttempted = true"))
        assertTrue(backend.contains("NotificationListenerAccessGrant.grant(android.os.Process.myUid() / 100000"))
    }
}
