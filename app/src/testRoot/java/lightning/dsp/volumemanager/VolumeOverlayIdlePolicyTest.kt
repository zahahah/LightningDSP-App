package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumeOverlayIdlePolicy
import lightning.dsp.volumemanager.ui.VolumeOverlayIdlePolicy.Interaction
import org.junit.Assert.*
import org.junit.Test

class VolumeOverlayIdlePolicyTest {
    @Test fun idleTimeStartsAtTheLastInteractionRatherThanTheFirstShow() {
        val policy = VolumeOverlayIdlePolicy(3300, 0)
        assertEquals(1300L, policy.remainingMillis(2000))
        policy.activity(2000)
        assertEquals(3300L, policy.remainingMillis(2000))
        assertEquals(1L, policy.remainingMillis(5299))
        assertEquals(0L, policy.remainingMillis(5300))
    }

    @Test fun holdingOrDraggingCannotExpireEvenIfAnotherShowRefreshesThePanel() {
        val policy = VolumeOverlayIdlePolicy(3300, 0)
        policy.setInteraction(Interaction.TOUCH, true, 1000)
        policy.activity(2000) // e.g. a volume-key refresh while a slider is held
        assertNull(policy.remainingMillis(20000))
        policy.setInteraction(Interaction.TOUCH, false, 20000)
        assertEquals(3300L, policy.remainingMillis(20000))
        assertEquals(0L, policy.remainingMillis(23300))
    }

    @Test fun focusAndHoverRemainActiveWhenTheTouchEnds() {
        val policy = VolumeOverlayIdlePolicy(3300, 0)
        for (kind in Interaction.entries) policy.setInteraction(kind, true, 1000)
        policy.setInteraction(Interaction.TOUCH, false, 2000)
        policy.setInteraction(Interaction.HOVER, false, 3000)
        policy.setInteraction(Interaction.FOCUS, false, 4000)
        assertNull(policy.remainingMillis(10000))
        policy.setInteraction(Interaction.ACCESSIBILITY, false, 10000)
        assertEquals(3300L, policy.remainingMillis(10000))
        assertEquals(0L, policy.remainingMillis(13300))
    }

    @Test fun explicitCloseCannotBeRescuedByHoverOrDowngradedByIdleDismissal() {
        val policy = VolumeOverlayIdlePolicy(3300, 0)
        policy.requestDismissal(explicit = true)
        policy.setInteraction(Interaction.HOVER, true, 1000)
        assertFalse(policy.mayResumeOnInteraction())
        policy.requestDismissal(explicit = false)
        assertFalse(policy.mayResumeOnInteraction())
    }

    @Test fun idleFadeCanBeRescuedButOnlyANewShowReopensExplicitClose() {
        val policy = VolumeOverlayIdlePolicy(3300, 0)
        policy.requestDismissal(explicit = false)
        policy.setInteraction(Interaction.TOUCH, true, 3400)
        assertTrue(policy.mayResumeOnInteraction())
        assertNull(policy.remainingMillis(3500))
        policy.requestDismissal(explicit = true)
        assertFalse(policy.mayResumeOnInteraction())
        policy.reopened(4000)
        assertTrue(policy.mayResumeOnInteraction())
        assertNull(policy.remainingMillis(10000)) // reopening does not release the touch
    }

    @Test fun anOlderTimestampCannotMoveTheDeadlineBackwards() {
        val policy = VolumeOverlayIdlePolicy(3300, 1000)
        policy.activity(500)
        assertEquals(3300L, policy.remainingMillis(1000))
    }
}
