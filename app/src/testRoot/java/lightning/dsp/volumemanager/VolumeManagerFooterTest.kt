package lightning.dsp.volumemanager

import java.io.File
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import lightning.dsp.volumemanager.ui.VolumeProfilePopupPositionProvider
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** View wiring only; permission dialogs, FAB cradle and Android rendering require a device. */
class VolumeManagerFooterTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
    private val ui = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningVolumeComposeUi.kt").readText()

    @Test fun profileMenuTouchesFooterButtonAndStaysInsideHorizontalBounds() {
        val anchor = IntRect(981, 2272, 1031, 2322)
        val size = IntSize(300, 260)
        val position = VolumeProfilePopupPositionProvider.calculatePosition(
            anchor, IntSize(1080, 2404), LayoutDirection.Ltr, size)
        assertEquals(anchor.top, position.y + size.height)
        assertEquals(anchor.right, position.x + size.width)
    }

    @Test fun footerReusesHomeButtonAndBottomAppBarWithACenteredCradle() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(app, "src/root/res/layout/volume_manager_footer.xml"))
        assertEquals(1, document.getElementsByTagName("lightning.dsp.view.FloatingToggleButton").length)
        val bars = document.getElementsByTagName("com.google.android.material.bottomappbar.BottomAppBar")
        assertEquals(1, bars.length)
        assertEquals("cradle", bars.item(0).attributes.getNamedItem("app:fabAnchorMode").nodeValue)
        assertEquals("center", bars.item(0).attributes.getNamedItem("app:fabAlignmentMode").nodeValue)
        assertTrue(ui.contains("VolumeManagerFooter(state, onShowOverlay)"))
    }

    @Test fun footerControlsDspPowerWithoutChangingManagerEnable() {
        val footer = ui.substringAfter("private fun VolumeManagerFooter(").substringBefore("private fun SettingRow(")
        assertTrue(footer.contains("R.string.key_powered_on"))
        assertTrue(footer.contains("putBoolean(powerKey, next)"))
        assertTrue(footer.contains("ACTION_PREFERENCES_UPDATED"))
        assertTrue(footer.contains("toggleOnClick = false"))
        assertTrue(footer.contains("isToggled = dspPowered"))
        assertFalse(footer.contains("setEnabled("))
        assertFalse(footer.contains("setPerAppEnabled"))
        assertTrue(footer.contains("navigationBarsPadding()"))
    }

    @Test fun floatingShortcutAndProfilesAreAtFooterEndWithoutChangingPower() {
        val topBar = ui.substringAfter("topBar = {").substringBefore("bottomBar = {")
        assertFalse(topBar.contains("onShowOverlay"))
        val footer = ui.substringAfter("private fun VolumeManagerFooter(").substringBefore("private fun SettingRow(")
        assertTrue(footer.contains("Modifier.align(Alignment.CenterStart).padding(start = 12.dp)"))
        assertTrue(footer.contains("Modifier.fillMaxWidth().height(72.dp)"))
        assertTrue(footer.contains("Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))"))
        assertTrue(footer.contains("IconButton(onClick = onShowOverlay,"))
        assertTrue(footer.contains("IconButton(onClick = { profileMenuOpen = true })"))
        assertTrue(footer.indexOf("IconButton(onClick = onShowOverlay,") <
            footer.indexOf("IconButton(onClick = { profileMenuOpen = true })"))
        val layout = File(app, "src/root/res/layout/volume_manager_footer.xml").readText()
        assertFalse(layout.contains("app:menu="))
        assertTrue(layout.contains("android:background=\"@android:color/transparent\""))
        assertFalse(layout.contains("android:elevation="))
        assertTrue(layout.contains("android:stateListAnimator=\"@null\""))
        assertTrue(ui.contains("R.id.volume_manager_bottom_bar)\n                        .apply {\n                            elevation = 0f"))
        assertTrue(layout.contains("app:backgroundTint=\"@android:color/transparent\""))
    }

    @Test fun managerEnableRemainsVisibleAbovePerAppControls() {
        assertTrue(ui.contains("item(key = \"manager_controls\")"))
        assertTrue(ui.contains("ManagerStatus(state = state, onEnabledChange = onManagerEnabledChange)"))
        val controls = ui.substringAfter("private fun ManagerStatus(").substringBefore("private fun ManagerStatusMessage(")
        assertTrue(controls.indexOf("Enable volume manager") < controls.indexOf("Per-app volume control"))
        assertTrue(controls.contains("Arrangement.spacedBy(10.dp)"))
        assertTrue(ui.contains("if (!checked) Text(subtitle"))
        assertTrue(ui.contains("item(key = \"manager_dsp_card_spacing\")"))
        assertTrue(ui.contains("Spacer(Modifier.height(8.dp))"))
    }

    @Test fun bothAppGroupsRemainAvailableWithoutEditingAndProfilesAreScopedToPerAppControl() {
        assertTrue(ui.contains("val groups = if (!compact) listOf("))
        assertTrue(ui.contains("Triple(\"Inactive\", inactiveApps, inactiveBounds)"))
        assertTrue(ui.contains("if (state.perAppEnabled) {\n            Box(Modifier.align(Alignment.CenterEnd)"))
        val picker = ui.substringAfter("private fun VolumeProfilePicker(").substringBefore("private fun ManagerStatus(")
        assertTrue(picker.contains("state.selectedProfileId"))
        assertTrue(picker.contains("LightningDspVolumeManager.createProfile(newName)"))
        assertTrue(picker.contains("LightningDspVolumeManager.selectProfile(profile.id)"))
        assertTrue(picker.contains("onLongClickLabel = \"Rename \${profile.name}\""))
        assertTrue(picker.contains("LightningDspVolumeManager.renameProfile(profile.id, newName)"))
        assertTrue(picker.contains("widthIn(min = 176.dp, max = 220.dp)"))
        assertTrue(ui.contains("painterResource(R.drawable.ic_volume_profiles)"))
        assertTrue(ui.contains("Modifier.fillMaxWidth().height(56.dp),\n                                verticalAlignment = Alignment.CenterVertically"))
    }
}
