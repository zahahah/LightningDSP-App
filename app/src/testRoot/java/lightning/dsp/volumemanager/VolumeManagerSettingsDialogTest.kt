package lightning.dsp.volumemanager

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only; inset geometry, scrolling and visual fit need Android verification. */
class VolumeManagerSettingsDialogTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private val source get() = File(app,
        "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt")
        .readText().substringAfter("private fun showSettings()")

    @Test fun dialogUsesAppBoundsAndCapsHeightWithoutAlwaysStretchingShortContent() {
        assertTrue(source.contains("findViewById<View>(android.R.id.content)"))
        assertTrue(source.contains("appContent.width * 0.94f"))
        assertTrue(source.contains("appContent.height * 0.80f"))
        assertTrue(source.contains("window.setGravity(Gravity.CENTER)"))
        assertTrue(source.contains("window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)"))
        assertTrue(source.contains("if (decor.height > maximumHeight)"))
        assertTrue(source.contains("window.setLayout(targetWidth, maximumHeight)"))
        assertFalse(source.contains("resources.displayMetrics.heightPixels"))
    }

    @Test fun longSettingsScrollInsideTheHeightCapWhileActionsRemainOutside() {
        assertTrue(source.contains("val scrollingControls = ScrollView(settingsContext)"))
        assertTrue(source.contains(".setView(scrollingControls)"))
        assertTrue(source.contains(".setPositiveButton(android.R.string.ok, null)"))
        assertTrue(source.contains("decor.height - scrollingControls.height"))
        assertTrue(source.contains("height = (maximumHeight - fixedHeight).coerceAtLeast(1)"))
        assertTrue(source.contains(".setBackgroundInsetStart(0).setBackgroundInsetEnd(0)"))
    }

    @Test fun spacedSectionsPreserveExistingSettingsAndLeaveFloatingThemeLast() {
        val sections = listOf("behavior", "app_list", "dsp_card", "floating_controls")
            .map { "addSection(R.string.ldsp_vm_settings_$it)" }
        sections.zipWithNext().forEach { (first, next) ->
            assertTrue(source.indexOf(first) >= 0)
            assertTrue(source.indexOf(first) < source.indexOf(next))
        }
        val themeSection = source.indexOf("setText(R.string.ldsp_vm_floating_theme_section)")
        assertTrue(themeSection > source.indexOf(sections.last()))
        assertTrue(themeSection > source.lastIndexOf("controls.addView(MaterialSwitch"))
        assertTrue(source.contains("ViewCompat.setAccessibilityHeading(this, true)"))
        assertTrue(source.contains("setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)"))
        for (setting in listOf("ldsp_vm_per_app", "ldsp_vm_startup", "ldsp_vm_show_dsp",
            "ldsp_vm_hide_system_apps", "ldsp_vm_group_system_streams", "ldsp_vm_media_icon_tap_expand",
            "ldsp_vm_hide_inactive_dsp", "ldsp_vm_grant_overlay")) {
            assertTrue("Missing existing setting $setting", source.contains("setText(R.string.$setting)"))
        }
    }

    @Test fun floatingThemeButtonHasExtraSpaceAboveIt() {
        val button = source.substringAfter("val floatingThemeButton = MaterialButton(settingsContext)")
            .substringBefore("fun updateFloatingThemeLabel")
        assertTrue(button.contains("topMargin = (8 * resources.displayMetrics.density).toInt()"))
    }

    @Test fun monochromeTrackIsOptInAndUsesTheExistingSharedPanelPreferences() {
        assertTrue(source.contains("getSharedPreferences(LightningVolumePanel.PREFERENCES, MODE_PRIVATE)"))
        assertTrue(source.contains("setText(R.string.ldsp_vm_monochrome_dsp_bar)"))
        assertTrue(source.contains("preferences.getBoolean(\"dsp_bar_monochrome\", false)"))
        assertTrue(source.contains("putBoolean(\"dsp_bar_monochrome\", checked).apply()"))
        val english = File(app, "src/root/res/values/volume_settings_sections.xml").readText()
        val portuguese = File(app, "src/root/res/values-pt/volume_settings_sections.xml").readText()
        assertTrue(english.contains("Enable monochrome LightningDSP bar"))
        assertTrue(portuguese.contains("Ativar barra LightningDSP monocromática"))
    }
}
