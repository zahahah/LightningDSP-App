package lightning.dsp.volumemanager

import java.io.File
import lightning.dsp.volumemanager.ui.MediaGroupExpansionPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class MediaGroupExpansionPolicyTest {
    @Test fun managerStartsExpandedWhileFloatingPanelStartsCollapsed() {
        assertTrue(Policy.initiallyExpanded(false))
        assertFalse(Policy.initiallyExpanded(true))
    }

    @Test fun defaultAndFullManagerKeepTheOriginalExpansionChevron() {
        assertFalse(Policy.useIconTap(true, false, true, false))
        assertFalse(Policy.useIconTap(false, true, true, false))
        for (expanded in listOf(false, true)) assertTrue(Policy.showChevron(true, expanded, false, true))
    }

    @Test fun optInHidesOnlyTheCollapsedArrowAndKeepsCollapseAvailable() {
        assertTrue(Policy.useIconTap(true, true, true, false))
        assertFalse(Policy.showChevron(true, false, true, true))
        assertTrue(Policy.showChevron(true, true, true, true))
    }

    @Test fun hiddenMediaKeepsFallbackButtonAndUngroupedOrEditingDoesNotUseTap() {
        assertTrue(Policy.showChevron(true, false, true, false))
        assertFalse(Policy.showChevron(false, false, true, true))
        assertFalse(Policy.useIconTap(true, true, false, false))
        assertFalse(Policy.useIconTap(true, true, true, true))
    }

    @Test fun optionIsDefaultOffBackedUpAndGestureIsOnlyOnTheBaseIcon() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        fun source(path: String) = File(app, "src/root/java/lightning/dsp/$path.kt").readText()
        val ui = source("volumemanager/ui/LightningVolumeComposeUi")
        val activity = source("volumemanager/ui/LightningDspVolumeManagerActivity")
        val codec = source("volumemanager/backup/VolumeManagerBackupSettings")
        assertTrue(ui.contains("preferences.getBoolean(MediaGroupExpansionPolicy.PREFERENCE_KEY, false)"))
        assertTrue(ui.contains("mutableStateOf(MediaGroupExpansionPolicy.initiallyExpanded(floatingPanel))"))
        assertTrue(activity.contains("putBoolean(MediaGroupExpansionPolicy.PREFERENCE_KEY, checked)"))
        assertTrue(codec.contains("\"media_icon_hold_expand\""))
        assertTrue(ui.contains("onIconClick = if (stream == AudioManager.STREAM_MUSIC && iconTapExpansion)"))
        assertTrue(ui.contains("({ secondaryExpanded = !secondaryExpanded }) else null"))
        assertTrue(ui.contains("streamIconTint, onIconClick)"))
        assertTrue(ui.contains("else MaterialTheme.colorScheme.onSurface, streamIconTint)"))
        assertEquals(1, Regex("Modifier.clickable").findAll(ui).count())
        assertTrue(ui.contains("onClick = onIconClick, role = Role.Button"))
        assertFalse(ui.contains("onIconLongClick"))
        assertFalse(ui.substringAfter("private fun SystemVolumeControls(").contains("onLongClickLabel"))
        assertTrue(ui.contains("if (stream == AudioManager.STREAM_MUSIC && showExpansionChevron) ExpansionButton()"))
    }
}
