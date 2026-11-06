package lightning.dsp.session.shared

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackObservationGenerationTest {
    @Test fun resumeDuringAnInspectionInvalidatesItsInactiveResult() {
        val generation = PlaybackObservationGeneration()
        val captured = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var accepted = true
        val worker = Thread {
            val oldDump = generation.snapshot()
            captured.countDown()
            check(finish.await(2, TimeUnit.SECONDS))
            accepted = generation.isCurrent(oldDump)
        }
        worker.start()
        assertTrue(captured.await(2, TimeUnit.SECONDS))
        generation.invalidate() // playback resumed while the old inspection was in flight
        finish.countDown()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertFalse(accepted)
        assertTrue(generation.isCurrent(generation.snapshot()))
    }

    @Test fun laterPlaybackChangesNeverAcceptEarlierObservations() {
        val generation = PlaybackObservationGeneration()
        val initial = generation.snapshot()
        repeat(10) { generation.invalidate() }
        assertFalse(generation.isCurrent(initial))
    }
}
