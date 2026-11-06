package lightning.dsp.session.shared

import java.util.concurrent.atomic.AtomicLong

/** A playback callback invalidates an observation started before that callback. */
internal class PlaybackObservationGeneration {
    private val generation = AtomicLong()
    fun invalidate() { generation.incrementAndGet() }
    fun snapshot(): Long = generation.get()
    fun isCurrent(observed: Long): Boolean = generation.get() == observed
}
