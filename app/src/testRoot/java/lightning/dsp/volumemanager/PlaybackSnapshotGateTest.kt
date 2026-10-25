package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.PlaybackSnapshotGate
import lightning.dsp.volumemanager.backend.PlaybackSnapshotGate.Update
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSnapshotGateTest {
    @Test fun unchangedPlayersDoNotEmitRepeatedUiSnapshots() {
        val gate = PlaybackSnapshotGate()
        assertEquals(Update.SNAPSHOT, gate.next("player:1001:active", 100))
        for (time in listOf(100L, 600L, 1_100L, 5_099L)) {
            assertEquals(Update.NONE, gate.next("player:1001:active", time))
        }
    }

    @Test fun heartbeatIsBoundedAndDoesNotBecomeAnotherSnapshot() {
        val gate = PlaybackSnapshotGate()
        assertEquals(Update.SNAPSHOT, gate.next("no players", 0))
        assertEquals(Update.HEARTBEAT, gate.next("no players", 5_000))
        assertEquals(Update.NONE, gate.next("no players", 5_001))
        assertEquals(Update.NONE, gate.next("no players", 9_999))
        assertEquals(Update.HEARTBEAT, gate.next("no players", 10_000))
    }

    @Test fun playerOrErrorChangesArePublishedImmediatelyAndResetTheHeartbeat() {
        val gate = PlaybackSnapshotGate()
        assertEquals(Update.SNAPSHOT, gate.next("idle", 0))
        assertEquals(Update.SNAPSHOT, gate.next("active", 100))
        assertEquals(Update.SNAPSHOT, gate.next("active:error", 200))
        assertEquals(Update.SNAPSHOT, gate.next("active", 300))
        assertEquals(Update.NONE, gate.next("active", 5_299))
        assertEquals(Update.HEARTBEAT, gate.next("active", 5_300))
    }

    @Test fun clockRollbackPreservesLivenessWithoutFloodingSnapshots() {
        val gate = PlaybackSnapshotGate(100)
        assertEquals(Update.SNAPSHOT, gate.next("same", 1_000))
        assertEquals(Update.HEARTBEAT, gate.next("same", 10))
        assertEquals(Update.NONE, gate.next("same", 11))
        assertEquals(Update.HEARTBEAT, gate.next("same", 110))
    }
}
