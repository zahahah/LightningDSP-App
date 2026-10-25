package lightning.dsp.volumemanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AboutCreditsTest {
    @Test fun aboutLinksAndCreditsUseLightningDspRepositoriesAndRequestedOrder() {
        val app = sequenceOf(File("."), File("app")).first {
            File(it, "src/root/AndroidManifest.xml").isFile
        }
        val about = File(app, "src/main/res/xml/app_about_preferences.xml").readText()
        val appCredits = about.substringAfter("@string/credits_app_implementation")
            .substringBefore("@string/credits_core_library_implementation")
        val coreCredits = about.substringAfter("@string/credits_core_library_implementation")
            .substringBefore("@string/key_translators")

        assertTrue(about.contains("android:data=\"https://github.com/zahahah/RootJamesDSP\""))
        assertTrue(about.contains("android:data=\"https://github.com/zahahah/RootJamesDSP/releases\""))
        assertFalse(about.contains("credits_project_translate"))
        assertTrue(appCredits.indexOf("credits_lightning_zahah") < appCredits.indexOf("credits_thepbone"))
        assertTrue(coreCredits.indexOf("credits_lightning_zahah") < coreCredits.indexOf("credits_james_fung"))

        val strings = File(app, "src/main/res/values/strings.xml").readText()
        assertFalse(strings.contains("App &amp; rootless implementation developed by"))
        assertFalse(strings.contains("name=\"credits_app\""))
        assertFalse(strings.contains("name=\"credits_dsp\""))
    }
}
