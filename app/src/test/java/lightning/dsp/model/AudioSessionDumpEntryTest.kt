package lightning.dsp.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionDumpEntryTest {
    @Test fun systemSoundUsagesAreOptInAtClassifierBoundary() {
        assertFalse(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_NOTIFICATION"))
        assertFalse(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_NOTIFICATION_EVENT"))
        assertFalse(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_ASSISTANCE_SONIFICATION"))

        assertTrue(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_NOTIFICATION", true))
        assertTrue(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_NOTIFICATION_EVENT", true))
        assertTrue(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_ASSISTANCE_SONIFICATION", true))
    }

    @Test fun enablingSystemSoundsStillExcludesCallsAndAlarms() {
        assertFalse(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_VOICE_COMMUNICATION", true))
        assertFalse(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_ALARM", true))
        assertTrue(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_UNKNOWN", true))
        assertTrue(AudioSessionDumpEntry.isUsageRecordable("AUDIO_USAGE_MEDIA", true))
    }
}
