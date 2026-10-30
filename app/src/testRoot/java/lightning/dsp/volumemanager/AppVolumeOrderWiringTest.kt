package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks supplement the model tests; they do not validate Android touch rendering. */
class AppVolumeOrderWiringTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
    private fun source(name: String) = File(app, "src/root/java/lightning/dsp/volumemanager/ui/$name.kt").readText()

    @Test fun bothPanelsComputeTheirRowsFromObservableOrderBeforeLazyContent() {
        val ui = source("LightningVolumeComposeUi")
        val manager = ui.substringAfter("internal fun LightningVolumeManagerScreen(")
            .substringBefore("internal fun LightningVolumeFloatingPanel(")
        val floating = ui.substringAfter("internal fun LightningVolumeFloatingPanel(")
            .substringBefore("private fun readHiddenAppIds(")
        for (panel in listOf(manager, floating)) {
            assertTrue(panel.contains("val savedAppOrder by rememberAppVolumeOrder(panelPrefs)"))
            assertTrue(panel.indexOf("val activeApps =") in 0 until panel.indexOf("LazyColumn("))
            assertFalse(panel.contains("getString(\"order_active\""))
            assertFalse(panel.contains("getString(\"order_inactive\""))
        }
        val store = source("AppVolumeOrderPreferences")
        assertTrue(store.contains("mutableStateOf(AppVolumeOrderPreferences.read(preferences))"))
        assertTrue(store.contains("state.value = AppVolumeOrderPreferences.read(preferences)"))
        assertTrue(store.contains(".putString(ORDER_KEY, order.apps.joinToString(\"|\"))"))
        assertTrue(store.contains(".putStringSet(PINS_KEY, order.pinned.toSet())"))
    }

    @Test fun pinIndicatorIsEditOnlyAndFloatingRowsNeverEnableEditing() {
        val ui = source("LightningVolumeComposeUi")
        val track = ui.substringAfter("private fun AppVolumeTrack(").substringBefore("private fun AppOptions(")
        assertTrue(track.contains("editing: Boolean = false"))
        assertTrue(track.contains("if (editing && pinned) Icon(Icons.Default.PushPin"))
        val floating = ui.substringAfter("internal fun LightningVolumeFloatingPanel(")
            .substringBefore("private fun readHiddenAppIds(")
        assertTrue(floating.contains("appOrder.displayOrder("))
        assertFalse(floating.contains("editing = true"))
        assertFalse(floating.contains("onPinnedChange ="))
    }

    @Test fun pinActionOnlyLivesInMoreOptionsAndIndicatorIsConditionalAndLeading() {
        val ui = source("LightningVolumeComposeUi")
        val track = ui.substringAfter("private fun AppVolumeTrack(").substringBefore("private fun AppOptions(")
        assertTrue(track.contains("leading = {\n            if (editing && pinned) Icon(Icons.Default.PushPin"))
        assertTrue(track.contains("R.string.ldsp_vm_pinned_app, app.label"))
        val options = ui.substringAfter("private fun AppOptions(").substringBefore("internal fun TrackSurface(")
        assertTrue(options.contains("if (pinned) R.string.ldsp_vm_unpin else R.string.ldsp_vm_pin"))
        assertTrue(options.contains("onPinnedChange(!pinned)"))
        assertTrue(options.contains("if (onPinnedChange != null)"))
        assertTrue(ui.contains("onPinnedChange = if (editing)"))
        val surface = ui.substringAfter("internal fun TrackSurface(").substringBefore("private fun RowScope.TrackContent(")
        assertTrue(surface.indexOf("leading()") in 0 until surface.indexOf("BoxWithConstraints("))
        assertEquals(1, Regex("Icons.Default.PushPin").findAll(ui).count())
    }
}
