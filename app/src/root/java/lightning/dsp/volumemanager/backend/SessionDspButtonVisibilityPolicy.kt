package lightning.dsp.volumemanager.backend

/** The master setting gates both on-demand and permanent controls in Session/Fusion modes. */
internal object SessionDspButtonVisibilityPolicy {
    fun shouldShow(modeEligible: Boolean, enabled: Boolean, always: Boolean, revealed: Boolean) =
        modeEligible && enabled && (always || revealed)

    fun onIconClick(modeEligible: Boolean, enabled: Boolean, always: Boolean, revealed: Boolean) =
        if (modeEligible && enabled && !always) !revealed else false
}
