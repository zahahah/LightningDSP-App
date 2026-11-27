package lightning.dsp.volumemanager

import java.io.File
import lightning.dsp.volumemanager.ui.WorkProfileVisibilityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkProfileVisibilityPolicyTest {
    @Test fun accessibleWorkAppsAreVisibleByDefaultWithoutChangingPersonalApps() {
        val showWorkApps = WorkProfileVisibilityPolicy.DEFAULT_SHOW_WORK_APPS
        assertTrue(WorkProfileVisibilityPolicy.includes(workProfile = true, showWorkProfileApps = showWorkApps))
        assertTrue(WorkProfileVisibilityPolicy.includes(workProfile = false, showWorkProfileApps = showWorkApps))
    }

    @Test fun hidingWorkAppsDoesNotHidePersonalCopyOfTheSamePackage() {
        assertFalse(WorkProfileVisibilityPolicy.includes(workProfile = true, showWorkProfileApps = false))
        assertTrue(WorkProfileVisibilityPolicy.includes(workProfile = false, showWorkProfileApps = false))
    }

    @Test fun workAppVisibilityLivesInVolumeManagerSettingsAndRowsAreBadged() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val compose = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningVolumeComposeUi.kt").readText()
        assertTrue(compose.contains("\"show_work_profile_apps\", WorkProfileVisibilityPolicy.DEFAULT_SHOW_WORK_APPS"))
        assertFalse(compose.contains("work_profile_visibility"))
        assertFalse(compose.contains("Show work profile apps"))
        assertTrue(compose.contains("WorkProfileBadge"))
        assertTrue(compose.contains("Icons.Default.Work"))
        assertFalse(compose.contains("work_profile_processing"))

        val activity = File(app, "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt").readText()
        val settings = activity.substringAfter("private fun showSettings()")
        assertTrue(settings.contains("WorkProfileAppsPolicy.hasAccessibleWorkProfile(this)"))
        assertTrue(settings.contains("R.string.ldsp_vm_show_work_profile_apps"))
        assertTrue(settings.contains("WorkProfileVisibilityPolicy.DEFAULT_SHOW_WORK_APPS"))
        assertFalse(settings.contains("process_work_profile_audio"))
        assertTrue(WorkProfileVisibilityPolicy.DEFAULT_SHOW_WORK_APPS)

        val audioXml = File(app, "src/main/res/xml/app_audio_format_preferences.xml").readText()
        assertTrue(audioXml.contains("key_audioformat_process_work_profile_audio"))
        assertTrue(audioXml.contains("android:persistent=\"false\""))
        val audioSettings = File(app, "src/main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt").readText()
        assertTrue(audioSettings.contains("updateProcessWorkProfileAudio()"))
        assertTrue(audioSettings.contains("process_work_profile_audio\", newValue as Boolean"))
        assertTrue(audioSettings.contains("WorkProfileAppsPolicy.hasAccessibleWorkProfile(requireContext())"))
        val appVisibilityPolicy = File(app, "src/main/java/lightning/dsp/utils/WorkProfileAppsPolicy.kt").readText()
        assertTrue(appVisibilityPolicy.contains("fun hasAccessibleWorkProfile(context: Context)"))

        assertTrue(WorkProfileVisibilityPolicy.DEFAULT_PROCESS_WORK_PROFILE_AUDIO)

        val sessionDatabase = File(app, "src/main/java/lightning/dsp/session/root/RootSessionDatabase.kt").readText()
        assertTrue(sessionDatabase.contains("workProfileAudioAllowed"))
        assertTrue(sessionDatabase.contains("getBoolean(\"process_work_profile_audio\", true)"))
        assertTrue(sessionDatabase.contains("isPackageDspEnabled(packageName, session.uid) && enabled"))
        assertTrue(sessionDatabase.contains("if (app.isGlobalMode) return true"))
        assertTrue(sessionDatabase.contains("if (app.isAppMixerMode) return false"))
        assertTrue(compose.contains("val sessionMode = !processingModes.first && !processingModes.second"))
    }
}
