package lightning.dsp.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ProcessingNotificationTextContractTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private fun source(path: String) = File(app, path).readText()

    @Test fun everyPersistentProcessingModeUsesTheSameTitleAndPreservesDisabledState() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(app, "src/main/res/values/strings.xml"))
        val strings = doc.getElementsByTagName("string")
        fun text(name: String) = (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == name }.textContent
        assertEquals("Audio Processing", text("notification_audio_processing_title"))
        assertEquals("Fusion mode relay working", text("notification_fusion_relay_working"))
        val helper = source("src/main/java/lightning/dsp/utils/notifications/ServiceNotificationHelper.kt")
        assertTrue(helper.contains(".setContentTitle(context.getString(R.string.notification_audio_processing_title))"))
        assertFalse(helper.contains("title: String"))
        assertTrue(helper.contains("!enabled -> context.getString(R.string.notification_processing_disabled_title)"))
        assertTrue(helper.contains("else R.string.notification_processing_disabled_title),"))
        assertTrue(helper.contains(".setSmallIcon(processingNotificationIcon(enabled))"))
    }

    @Test fun fusionWorkingTextIsUsedOnlyAfterSuccessfulStartOrRuleUpdate() {
        val controller = source("src/root/java/lightning/dsp/interop/appmixer/AppMixerModeController.kt")
        assertEquals(2, Regex("R.string.notification_fusion_relay_working").findAll(controller).count())
        assertTrue(controller.contains("running = supervisor.start()"))
        assertTrue(controller.contains("if (supervisor.updateRules(next, nextSystemSounds))"))
        assertTrue(controller.contains("Fusion Mode failed: $"))
        assertTrue(controller.contains("system sounds are excluded"))
        assertTrue(controller.contains("Fusion Mode relay stopped; coverage is unavailable until retry."))
    }
}
