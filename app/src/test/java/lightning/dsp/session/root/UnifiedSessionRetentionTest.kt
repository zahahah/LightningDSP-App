package lightning.dsp.session.root

import lightning.dsp.model.AudioSessionDumpEntry
import lightning.dsp.session.dump.data.SessionOwnerRef
import org.junit.Assert.*
import org.junit.Test

class UnifiedSessionRetentionTest {
    private fun entry(uid: Int = 10123, pkg: String = "emulator", active: Boolean? = false) =
        AudioSessionDumpEntry(uid, pkg, "USAGE_GAME", "CONTENT_TYPE_MUSIC", active)

    @Test fun missingPlayerCanRetainOnlyAPreviouslyVerifiedLiveOwnersExactFrameworkReference() {
        val state = UnifiedSessionRetention()
        var alive = true
        state.claim(12, 10123, 42) { alive }
        state.update(mapOf(12 to entry()), true)
        state.update(emptyMap(), true, setOf(SessionOwnerRef(12, 42, 10123)))
        assertTrue(state.shouldRetain(12, 10123, "emulator"))
        state.update(emptyMap(), true, setOf(SessionOwnerRef(12, 43, 10123)))
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.update(emptyMap(), true, setOf(SessionOwnerRef(12, 42, 10123)))
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.update(mapOf(12 to entry()), true)
        state.update(emptyMap(), true, null)
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.update(mapOf(12 to entry()), true)
        alive = false
        state.update(emptyMap(), true, setOf(SessionOwnerRef(12, 42, 10123)))
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
    }

    @Test fun liveClaimNeedsIndependentSessionIdentityAndKeepsIdleOnly() {
        val state = UnifiedSessionRetention()
        assertTrue(state.claim(12, 10123, 42) { true })
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.update(mapOf(12 to entry()), true)
        assertTrue(state.shouldRetain(12, 10123, "emulator"))
        assertFalse(state.shouldRetain(12, 10123, "another"))
        state.update(mapOf(12 to entry(10124)), true)
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.update(emptyMap(), true)
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
    }

    @Test fun deadOwnerUnavailableSnapshotAndOptOutWithdrawRetention() {
        val state = UnifiedSessionRetention()
        var alive = true
        state.claim(12, 10123, 42) { alive }
        state.update(mapOf(12 to entry()), true)
        state.markUnavailable()
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.update(mapOf(12 to entry()), true)
        alive = false
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.update(mapOf(12 to entry()), true)
        alive = true
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        state.claim(12, 10123, 42) { alive }
        state.update(mapOf(12 to entry()), false)
        state.update(mapOf(12 to entry()), true)
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
    }

    @Test fun expiryTriggersOneReconciliationWithoutAnotherDump() {
        val state = UnifiedSessionRetention()
        var now = 0L
        state.claim(12, 10123, 42) { now < 6_000L }
        state.update(mapOf(12 to entry()), true)
        assertFalse(state.reconcile(true))
        assertTrue(state.hasClaims())
        now = 6_000L
        assertTrue(state.reconcile(true))
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        assertFalse(state.hasClaims())
        assertFalse(state.reconcile(true))
    }

    @Test fun deathAndOptOutTriggerReconciliationWithoutAnotherDump() {
        val state = UnifiedSessionRetention()
        var alive = true
        state.claim(12, 10123, 42) { alive }
        state.update(mapOf(12 to entry()), true)
        alive = false
        assertTrue(state.reconcile(true))
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        alive = true
        state.claim(12, 10123, 42) { alive }
        state.update(mapOf(12 to entry()), true)
        assertTrue(state.reconcile(false))
        assertFalse(state.hasClaims())
        assertFalse(state.reconcile(false))
    }

    @Test fun unavailableProofAndPinReplacementRequestReconciliation() {
        val state = UnifiedSessionRetention()
        state.claim(12, 10123, 42) { true }
        state.update(mapOf(12 to entry()), true)
        state.markUnavailable()
        assertTrue(state.reconcile(true))
        state.update(mapOf(12 to entry()), true)
        state.claim(13, 10123, 42) { true }
        assertTrue(state.reconcile(true))
        assertFalse(state.reconcile(true))
    }

    @Test fun boundsIdentityAndExplicitCleanupProtectOtherSessions() {
        val state = UnifiedSessionRetention(1)
        assertFalse(state.claim(0, 10123, 42) { true })
        assertFalse(state.claim(12, 1000, 42) { true })
        assertFalse(state.claim(12, 10123, 0) { true })
        assertTrue(state.claim(12, 10123, 42) { true })
        assertFalse(state.claim(12, 10124, 43) { true })
        assertTrue(state.claim(13, 10123, 42) { true })
        state.update(mapOf(12 to entry(), 13 to entry()), true)
        assertFalse(state.shouldRetain(12, 10123, "emulator"))
        assertTrue(state.shouldRetain(13, 10123, "emulator"))
        assertFalse(state.claim(13, 10124, 43) { true })
        state.forget(13)
        assertTrue(state.claim(13, 10124, 43) { true })
        state.update(mapOf(13 to entry(10124)), true)
        assertTrue(state.shouldRetain(13, 10124, "emulator"))
        state.clear()
        assertFalse(state.shouldRetain(13, 10124, "emulator"))
    }
}
