package lightning.dsp.session.root

import org.junit.Assert.*
import org.junit.Test

class UnifiedSessionFocusTest {
    @Test fun focusedUiProcessCanCoverAudioProcessOfSameUniquePackage() {
        val focus = UnifiedSessionFocus(now = { 0L })
        assertTrue(focus.update(10001, 10, "emulator", true) { true })
        assertTrue(focus.reconcile(true))
        assertTrue(focus.isFocused(10001, "emulator"))
        assertFalse(focus.isFocused(10002, "emulator"))
        assertFalse(focus.isFocused(10001, "other"))
        // An unfocused worker must not erase a different process's focused window.
        assertTrue(focus.update(10001, 20, "emulator", false) { true })
        assertTrue(focus.isFocused(10001, "emulator"))
        assertFalse(focus.reconcile(true))
    }

    @Test fun lossDeathExpiryAndOptionOffWithdrawFocusWithoutAnotherDump() {
        val focus = UnifiedSessionFocus(now = { 0L })
        var alive = true
        focus.update(10001, 10, "emulator", true) { alive }
        focus.reconcile(true)
        alive = false
        assertFalse(focus.isFocused(10001, "emulator"))
        assertTrue(focus.reconcile(true))
        assertFalse(focus.hasOwners())
        focus.update(10001, 10, "emulator", true) { true }
        focus.reconcile(true)
        focus.update(10001, 10, "emulator", false) { true }
        assertTrue(focus.reconcile(true))
        focus.update(10001, 10, "emulator", true) { true }
        focus.reconcile(true)
        assertTrue(focus.reconcile(false))
        assertFalse(focus.isFocused(10001, "emulator"))
        assertFalse(focus.reconcile(false))
    }

    @Test fun unknownInvalidAndUnboundedOwnersCannotClaimFocus() {
        val focus = UnifiedSessionFocus(limit = 1, now = { 0L })
        assertFalse(focus.isFocused(10001, "emulator"))
        assertFalse(focus.update(1000, 10, "emulator", true) { true })
        assertFalse(focus.update(10001, 0, "emulator", true) { true })
        assertFalse(focus.update(10001, 10, "", true) { true })
        assertFalse(focus.update(10001, 10, "emulator", true) { false })
        assertTrue(focus.update(10001, 10, "emulator", true) { true })
        assertFalse(focus.update(10002, 20, "other", true) { true })
        focus.clear()
        assertFalse(focus.hasOwners())
    }

    @Test fun missingFocusLossExpiresEvenWhileAudioKeepsOwnerAlive() {
        var timestamp = 10L
        var audioLeaseExpiry = 6_010L
        val focus = UnifiedSessionFocus(now = { timestamp })
        focus.update(10001, 10, "emulator", true) { timestamp < audioLeaseExpiry }
        focus.reconcile(true)
        timestamp = 4_010L
        audioLeaseExpiry = 10_010L // Audio renewed the shared owner, not window focus.
        assertTrue(focus.isFocused(10001, "emulator"))
        timestamp = 6_010L
        audioLeaseExpiry = 12_010L
        assertFalse(focus.isFocused(10001, "emulator"))
        assertTrue(focus.reconcile(true))
        assertFalse(focus.hasOwners())
        assertFalse(focus.reconcile(true))
    }

    @Test fun onlyAnExplicitFocusReportRenewsFocusAndClockRollbackFailsClosed() {
        var timestamp = 10L
        val focus = UnifiedSessionFocus(now = { timestamp })
        focus.update(10001, 10, "emulator", true) { true }
        timestamp = 5_000L
        focus.update(10001, 10, "emulator", true) { true }
        timestamp = 6_010L
        assertTrue(focus.isFocused(10001, "emulator"))
        timestamp = 4_999L
        assertFalse(focus.isFocused(10001, "emulator"))
        focus.reconcile(true)
        assertFalse(focus.hasOwners())
    }
}
