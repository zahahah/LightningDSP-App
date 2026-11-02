package lightning.dsp.session.root

/** Delayed dumps and callbacks from replaced managers must not touch the global baseline. */
internal object SessionDumpAdmission {
    fun allows(isCurrentManager: Boolean, sessionScanningActive: Boolean): Boolean =
        isCurrentManager && sessionScanningActive
}
