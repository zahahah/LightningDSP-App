package lightning.dsp.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import lightning.dsp.R
import lightning.dsp.utils.notifications.processingNotificationIcon
import org.junit.Assert.*
import org.junit.Test

class StatusNotificationIconTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }

    @Test fun powerSelectsBoltOrCrossedOutBolt() {
        assertEquals(R.drawable.ic_lightning_status_24dp, processingNotificationIcon(true))
        assertEquals(R.drawable.ic_lightning_off_status_24dp, processingNotificationIcon(false))
    }

    @Test fun boltIsASingleMonochromeVectorNotAnOpaqueLauncherBackground() {
        val xml = File(app, "src/main/res/drawable/ic_lightning_status_24dp.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).documentElement
        assertEquals("24dp", root.getAttribute("android:width"))
        assertEquals("24dp", root.getAttribute("android:height"))
        assertEquals("vector", root.tagName)
        val paths = root.getElementsByTagName("path")
        assertEquals(1, paths.length)
        assertEquals("#FFFFFFFF", paths.item(0).attributes.getNamedItem("android:fillColor").nodeValue)
    }

    @Test fun disabledBoltIsAlsoAMonochromeStatusVector() {
        val xml = File(app, "src/main/res/drawable/ic_lightning_off_status_24dp.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).documentElement
        assertEquals("vector", root.tagName)
        assertEquals("24dp", root.getAttribute("android:width"))
        assertEquals("#FFFFFFFF", root.getElementsByTagName("path").item(0)
            .attributes.getNamedItem("android:fillColor").nodeValue)
        assertTrue(root.getElementsByTagName("path").item(0)
            .attributes.getNamedItem("android:pathData").nodeValue.contains("M3.27,2"))
    }

    @Test fun iconTracksPowerInLegacySessionAndMixerNotifications() {
        val helper = File(app, "src/main/java/lightning/dsp/utils/notifications/ServiceNotificationHelper.kt").readText()
        assertTrue(helper.contains(".setSmallIcon(processingNotificationIcon(enabled))"))
        assertEquals(2, Regex("enabled = enabled").findAll(helper).count())
        val service = File(app, "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeService.kt").readText()
        assertTrue(service.contains(".setSmallIcon(processingNotificationIcon("))
        assertTrue(service.contains("getBoolean(getString(R.string.key_powered_on), true)"))
        assertTrue(service.contains("registerOnSharedPreferenceChangeListener(powerListener)"))
        assertTrue(service.contains("unregisterOnSharedPreferenceChangeListener(powerListener)"))
        val channels = File(app, "src/main/java/lightning/dsp/utils/notifications/Notifications.kt").readText()
        assertTrue(channels.contains("buildNotificationChannel(CHANNEL_SERVICE_STATUS, if (isRootless()) IMPORTANCE_NONE else IMPORTANCE_LOW)"))
        assertTrue(channels.contains("const val CHANNEL_SERVICE_STATUS = \"service_status\""))
    }
}
