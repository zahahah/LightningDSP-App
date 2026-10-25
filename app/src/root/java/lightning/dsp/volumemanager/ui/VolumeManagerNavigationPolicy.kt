package lightning.dsp.volumemanager.ui

/** Only the mixer screen is transient; background audio settings and services are untouched. */
internal class VolumeManagerNavigationPolicy {
    private var restored = false
    var returnToMain = false
    var externalFlow = false

    fun restore(returnToMain: Boolean, externalFlow: Boolean) {
        if (restored) return
        this.returnToMain = returnToMain
        this.externalFlow = externalFlow
        restored = true
    }

    /** Result, denial/cancel and request failure all end the temporary navigation exemption. */
    fun externalFlowFinished() {
        externalFlow = false
    }

    fun stopped(changingConfigurations: Boolean, finishing: Boolean) {
        if (!changingConfigurations && !finishing && !externalFlow) returnToMain = true
    }
}
