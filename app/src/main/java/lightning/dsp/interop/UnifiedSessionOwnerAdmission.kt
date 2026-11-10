package lightning.dsp.interop

/** Pure admission decision; identity inputs must come from Binder, never reply payloads. */
internal object UnifiedSessionOwnerAdmission {
    fun matches(expectedUid: Int, expectedPid: Int, callbackUid: Int, callbackPid: Int) =
        expectedUid == callbackUid && expectedPid == callbackPid && expectedPid > 0

    fun accepts(callbackCompleted: Boolean, identityMatched: Boolean, ownerDead: Boolean, now: Long, deadline: Long) =
        callbackCompleted && identityMatched && !ownerDead && now < deadline
}
