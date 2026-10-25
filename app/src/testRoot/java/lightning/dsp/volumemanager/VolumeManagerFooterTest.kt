package lightning.dsp.volumemanager

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** View wiring only; permission dialogs, FAB cradle and Android rendering require a device. */
class VolumeManagerFooterTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
    private val ui = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningVolumeComposeUi.kt").readText()

    @Test fun footerReusesHomeButtonAndBottomAppBarWithACenteredCradle() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(app, "src/root/res/layout/volume_manager_footer.xml"))
        assertEquals(1, document.getElementsByTagName("lightning.dsp.view.FloatingToggleButton").length)
        val bars = document.getElementsByTagName("com.google.android.material.bottomappbar.BottomAppBar")
        assertEquals(1, bars.length)
        assertEquals("cradle", bars.item(0).attributes.getNamedItem("app:fabAnchorMode").nodeValue)
        assertEquals("center", bars.item(0).attributes.getNamedItem("app:fabAlignmentMode").nodeValue)
        assertTrue(ui.contains("if (!compact) VolumeManagerFooter(state.enabled, onManagerEnabledChange, onShowOverlay)"))
    }

    @Test fun footerUsesAuthoritativeManagerStateAndNeverChangesDspPower() {
        val footer = ui.substringAfter("private fun VolumeManagerFooter(").substringBefore("private fun SettingRow(")
        assertTrue(footer.contains("rememberUpdatedState(enabled)"))
        assertTrue(footer.contains("rememberUpdatedState(onEnabledChange)"))
        assertTrue(footer.contains("toggleOnClick = false"))
        assertTrue(footer.contains("currentChange(!currentEnabled)"))
        assertTrue(footer.contains("isToggled = enabled"))
        assertFalse(footer.contains("key_powered_on"))
        assertFalse(footer.contains("setPerAppEnabled"))
        assertTrue(footer.contains("navigationBarsPadding()"))
    }

    @Test fun floatingShortcutMovesFromToolbarToFooterEndMenuWithoutChangingPower() {
        val topBar = ui.substringAfter("topBar = {").substringBefore("bottomBar = {")
        assertFalse(topBar.contains("onShowOverlay"))
        val footer = ui.substringAfter("private fun VolumeManagerFooter(").substringBefore("private fun SettingRow(")
        assertTrue(footer.contains("rememberUpdatedState(onShowOverlay)"))
        assertTrue(footer.contains("item.itemId == R.id.volume_manager_show_overlay"))
        assertTrue(footer.contains("currentShowOverlay()"))
        val menu = File(app, "src/root/res/menu/volume_manager_footer.xml").readText()
        assertTrue(menu.contains("app:showAsAction=\"always\""))
        assertTrue(menu.contains("@+id/volume_manager_show_overlay"))
        assertTrue(menu.contains("app:iconTint=\"?attr/colorOnSurface\""))
        val layout = File(app, "src/root/res/layout/volume_manager_footer.xml").readText()
        assertTrue(layout.contains("app:menu=\"@menu/volume_manager_footer\""))
    }

    @Test fun topExplanationIsDisabledOnlyButErrorsStayVisibleWhenEnabled() {
        assertTrue(ui.contains("if (!state.enabled) {\n                        item(key = \"manager_controls\")"))
        assertTrue(ui.contains("else if (!state.connected || state.error != null || state.systemVolumeError != null)"))
        assertTrue(ui.contains("item(key = \"manager_diagnostics\") { ManagerStatusMessage(state) }"))
    }
}
