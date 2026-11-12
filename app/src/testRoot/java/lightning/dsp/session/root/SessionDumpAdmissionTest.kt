package lightning.dsp.session.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionDumpAdmissionTest {
    @Test fun lateOrReplacedManagerCallbacksCannotTouchGlobalBaseline() {
        assertTrue(SessionDumpAdmission.allows(true, true))
        assertFalse(SessionDumpAdmission.allows(true, false))
        assertFalse(SessionDumpAdmission.allows(false, true))
        assertFalse(SessionDumpAdmission.allows(false, false))
    }

    @Test fun serviceDetachesManagerBeforeTeardownAndFencesBothCallbacks() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").isFile }
        val source = File(app, "src/main/java/lightning/dsp/service/RootAudioProcessorService.kt").readText()
        val destroy = source.substringAfter("override fun onDestroy() {").substringBefore("override fun onSharedPreferenceChanged")
        assertTrue(destroy.indexOf("sessionDumpManager = null") < destroy.indexOf("oldSessionDumpManager?.destroy()"))
        assertTrue(destroy.indexOf("oldSessionDumpManager?.destroy()") < destroy.indexOf("app.rootSessionDatabase.clearSessions()"))
        assertTrue(source.split("SessionDumpAdmission.allows(!isServiceDisposing && sessionDumpManager === manager, !app.isGlobalMode)").size == 3)
        assertTrue(source.contains("if (isServiceDisposing || sessionDumpManager != null || app.isGlobalMode || app.isAppMixerMode) return"))
    }
}
