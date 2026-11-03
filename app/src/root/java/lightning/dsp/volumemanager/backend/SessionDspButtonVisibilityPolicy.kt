package lightning.dsp.volumemanager.backend

/** App-level buttons are on-demand unless the Session Mode setting requests them always. */
internal object SessionDspButtonVisibilityPolicy {
    fun shouldShow(sessionMode: Boolean, alwaysSetting: Boolean, always: Boolean, revealed: Boolean) =
        sessionMode && ((alwaysSetting && always) || revealed)
}
