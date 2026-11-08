package lightning.dsp.session.dump

import lightning.dsp.model.AudioSessionDumpEntry
import lightning.dsp.session.dump.data.AudioServiceDump
import lightning.dsp.session.dump.data.ISessionInfoDump
import org.junit.Assert.*
import org.junit.Test

class SessionDumpSelectionTest {
    private val empty = AudioServiceDump(hashMapOf())
    private val playing = AudioServiceDump(hashMapOf(42 to
        AudioSessionDumpEntry(10042, "player", "AUDIO_USAGE_MEDIA", "AUDIO_CONTENT_TYPE_MUSIC", true)))
    private val failed: () -> ISessionInfoDump? = { throw IllegalStateException("collection unavailable") }

    @Test fun preferredEmptySurvivesAlternativeFailure() {
        var failures = 0
        val result = SessionDumpSelection.collect({ empty }, listOf(failed, { null }), true) { failures++ }
        assertSame(empty, result)
        assertEquals(1, failures)
    }

    @Test fun alternateEmptySurvivesOtherFailures() {
        val result = SessionDumpSelection.collect(failed, listOf({ empty }, failed), true) {}
        assertSame(empty, result)
    }

    @Test fun laterPlayingObservationTakesPrecedenceOverEmpty() {
        val result = SessionDumpSelection.collect({ empty }, listOf({ empty }, { playing }), true) {}
        assertSame(playing, result)
    }

    @Test fun nullMeansEveryCollectorWasUnavailable() {
        val result = SessionDumpSelection.collect(failed, listOf({ null }, failed), true) {}
        assertNull(result)
    }

    @Test fun disabledFallbackReturnsPreferredEmptyWithoutCallingAlternatives() {
        val result = SessionDumpSelection.collect({ empty }, listOf({ error("must not run") }), false) {}
        assertSame(empty, result)
    }

    @Test fun preferredPlayingObservationDoesNotCallAlternatives() {
        val result = SessionDumpSelection.collect({ playing }, listOf({ error("must not run") }), true) {}
        assertSame(playing, result)
    }
}
