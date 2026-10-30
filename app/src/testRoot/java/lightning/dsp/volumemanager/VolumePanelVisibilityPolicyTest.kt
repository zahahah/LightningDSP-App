package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumePanelVisibilityPolicy
import org.junit.Assert.*
import org.junit.Test

class VolumePanelVisibilityPolicyTest {
    @Test fun `manager per-app and card switches are independent across truth table`() {
        for (manager in listOf(false, true)) for (perApp in listOf(false, true))
            for (card in listOf(false, true)) {
                val result = VolumePanelVisibilityPolicy.evaluate(manager, perApp, card, false, false, false)
                assertEquals(manager && perApp, result.showApps)
                assertEquals(card, result.showDspCard)
                assertTrue(result.showSystemControls)
            }
    }

    @Test fun `hide inactive requires engine availability and actual audio activity`() {
        for (available in listOf(false, true)) for (active in listOf(false, true)) {
            val result = VolumePanelVisibilityPolicy.evaluate(true, true, true, true, available, active)
            assertEquals(available && active, result.showDspCard)
        }
        assertFalse(VolumePanelVisibilityPolicy.evaluate(true, true, false, false, true, true).showDspCard)
    }

    @Test fun `saved app settings remain editable while inactive but shared UID audio controls stay disabled`() {
        assertFalse(VolumePanelVisibilityPolicy.canChangeAppAudio(false, true, true, true))
        assertTrue(VolumePanelVisibilityPolicy.evaluate(true, true, false, false, false, false).showApps)
        assertTrue(VolumePanelVisibilityPolicy.canChangeAppAudio(true, true, true, true))
        assertTrue(VolumePanelVisibilityPolicy.canChangeAppAudio(true, true, false, true))
        assertTrue(VolumePanelVisibilityPolicy.canChangeAppAudio(true, false, false, false))
    }

    @Test fun `call slider obeys explicit visibility preference even during communication`() {
        for (selected in listOf(false, true)) for (call in listOf(false, true))
            assertEquals(selected, VolumePanelVisibilityPolicy.showCallStream(selected, call))
    }
}
