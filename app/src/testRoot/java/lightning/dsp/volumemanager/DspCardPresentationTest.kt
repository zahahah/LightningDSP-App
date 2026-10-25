package lightning.dsp.volumemanager

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import lightning.dsp.volumemanager.ui.dspTrackColors
import lightning.dsp.volumemanager.ui.dspTrackTextColor
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DspCardPresentationTest {
    @Test fun bothTrackRegionsKeepReadableTextInBothThemes() {
        // Actual blue fill and floating neutral/base colors, plus light/dark themed surfaces.
        listOf(0xFF4997D0, 0xFF347FB6, 0xFFA6B6C4, 0xFF4B535B, 0xFF727C85,
            0xFF1B2831, 0xFFE0EDF7).forEach { value ->
            val background = Color(value)
            val foreground = dspTrackTextColor(background)
            val brighter = maxOf(background.luminance(), foreground.luminance())
            val darker = minOf(background.luminance(), foreground.luminance())
            assertTrue("Contrast for ${value.toString(16)}", (brighter + 0.05f) / (darker + 0.05f) >= 4.5f)
        }
    }

    @Test fun blueTrackStaysBlueInBothThemesAndMonochromeTracksAreNeutral() {
        for (floating in listOf(false, true)) {
            val darkBlue = dspTrackColors(false, false, floating)
            val lightBlue = dspTrackColors(false, true, floating)
            assertEquals(darkBlue, lightBlue)
            assertTrue(lightBlue.second.blue > lightBlue.second.red)
            for (light in listOf(false, true)) {
                val neutral = dspTrackColors(true, light, floating)
                assertNotEquals(lightBlue, neutral)
                for (background in listOf(neutral.first, neutral.second, lightBlue.second)) {
                    assertTrue(kotlin.math.abs(neutral.second.red - neutral.second.blue) < 0.1f)
                    val foreground = dspTrackTextColor(background)
                    val ratio = (maxOf(background.luminance(), foreground.luminance()) + 0.05f) /
                        (minOf(background.luminance(), foreground.luminance()) + 0.05f)
                    assertTrue(ratio >= 4.5f)
                }
            }
        }
    }

    @Test fun monochromePreferenceIsObservedLiveAndDefaultsOff() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val card = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeCard.kt").readText()
        assertTrue(card.contains("getBoolean(\"dsp_bar_monochrome\", false)"))
        assertTrue(card.contains("changed == null || changed == \"dsp_bar_monochrome\""))
        assertTrue(card.contains("dspTrackColors(monochromeBar, lightTheme, floating)"))
    }

    @Test fun cardHasNoSecondSurfaceAndOnlyExpandedEffectsAddBottomMargin() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val card = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeCard.kt").readText()
        assertFalse(Regex("(?m)^\\s*Surface\\(").containsMatchIn(card))
        assertTrue(card.contains("bottom = if (expanded) 8.dp else 0.dp"))
        assertFalse(card.contains("top = 10.dp, bottom = 10.dp"))
        assertTrue(card.contains("baseTrack.compositeOver(MaterialTheme.colorScheme.background)"))
    }

    @Test fun floatingDspButtonSharesTheStandardIconCenterWithoutRightPadding() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val card = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeCard.kt").readText()
        assertEquals(16f + 32f / 2, 10f + 44f / 2, 0f)
        assertTrue(card.contains("Row(modifier = Modifier.padding(start = if (floating) 10.dp else 0.dp)"))
        assertTrue(card.contains("Modifier.size(44.dp).clip(CircleShape)"))
        assertTrue(card.contains("end = if (floating) 0.dp else 14.dp"))
    }

    @Test fun reorderingIsPresentationOnlyAndFloatingLongPressStaysAnEffectAction() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val card = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeCard.kt").readText()
        assertTrue(card.contains("dsp_effect_display_order"))
        assertTrue(card.contains("VolumeDisplayOrder.move(effectOrder, id, target)"))
        assertTrue(card.contains("modifier = if (floating) Modifier else Modifier"))
        assertTrue(card.contains("onLongClick = if (floating)"))
        assertTrue(card.contains("selectedParameter = control"))
        assertTrue(card.contains("openEffect(effect)"))
        assertTrue(card.contains("key(effectId)"))
        assertTrue(card.contains("change.consume()"))
        assertTrue(card.contains("Move effect left"))
        assertTrue(card.contains("trackContentColor = baseText"))
        assertTrue(card.contains("progressContentColor = progressText"))
    }
}
