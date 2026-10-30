package lightning.dsp.volumemanager.ui

/** UI visibility only. Hiding the card never changes DSP power or saved player volumes. */
internal object VolumePanelVisibilityPolicy {
    data class Visibility(val showApps: Boolean, val showDspCard: Boolean, val showSystemControls: Boolean = true)

    fun evaluate(managerEnabled: Boolean, perAppEnabled: Boolean, cardEnabled: Boolean,
        hideInactiveCard: Boolean, engineAvailable: Boolean, audioActive: Boolean) = Visibility(
        showApps = managerEnabled && perAppEnabled,
        showDspCard = cardEnabled && (!hideInactiveCard || (engineAvailable && audioActive))
    )

    @Suppress("UNUSED_PARAMETER")
    fun canChangeAppAudio(controllable: Boolean, managerEnabled: Boolean, connected: Boolean,
        perAppEnabled: Boolean) = controllable

    @Suppress("UNUSED_PARAMETER")
    fun showCallStream(selected: Boolean, inCommunication: Boolean) = selected
}
