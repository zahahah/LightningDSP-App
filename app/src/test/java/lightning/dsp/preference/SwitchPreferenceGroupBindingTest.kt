package lightning.dsp.preference

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Binding/lifecycle contracts; actual animation appearance requires a device. */
class SwitchPreferenceGroupBindingTest {
    private val source = sequenceOf(File("."), File("app"))
        .map { File(it, "src/main/java/lightning/dsp/preference/SwitchPreferenceGroup.kt") }
        .first { it.isFile }.readText()

    private val bind = source.substringAfter("override fun onBindViewHolder")
        .substringBefore("override fun onPrepareAddPreference")

    @Test fun bindingUsesCurrentStateWithoutReplayingAnAnimation() {
        assertTrue(bind.contains("cancelHeaderAnimation()"))
        assertTrue(bind.contains("alpha = if (this@SwitchPreferenceGroup.state) TRANSITION_MAX else TRANSITION_MIN"))
        assertFalse(bind.contains("animateHeaderState("))
        assertFalse(bind.contains("ValueAnimator.ofInt"))
    }

    @Test fun bindingRemovesTheOldSwitchListenerBeforeApplyingState() {
        val initialization = bind.substringAfter("as MaterialSwitch).apply {")
        assertTrue(initialization.contains("setOnCheckedChangeListener(null)"))
        assertTrue(initialization.indexOf("setOnCheckedChangeListener(null)") <
            initialization.indexOf("isChecked = state"))
        assertTrue(initialization.indexOf("isChecked = state") <
            initialization.indexOf("setOnCheckedChangeListener {"))
    }

    @Test fun detachedPreferenceCannotKeepAnimatingAnOldHolder() {
        val detached = source.substringAfter("override fun onDetached()")
            .substringBefore("override fun onPrepareAddPreference")
        assertTrue(detached.contains("cancelHeaderAnimation()"))
        assertTrue(detached.contains("itemView = null"))
        assertTrue(detached.contains("switch = null"))
        val cancellation = source.substringAfter("private fun cancelHeaderAnimation()")
            .substringBefore("private fun animateHeaderState")
        assertTrue(cancellation.contains("cancel()"))
        assertTrue(cancellation.contains("removeAllUpdateListeners()"))
    }
}
