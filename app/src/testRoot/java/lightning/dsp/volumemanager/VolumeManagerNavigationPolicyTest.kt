package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.ui.VolumeManagerNavigationPolicy
import org.junit.Assert.*
import java.io.File
import org.junit.Test

class VolumeManagerNavigationPolicyTest {
    @Test fun activityWiresTemporarySettingsAndSavedPolicyWithoutStoppingAudioService() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/root/AndroidManifest.xml").isFile }
        val source = File(app,
            "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt").readText()
        assertTrue(source.contains("by viewModels<NavigationState>()"))
        assertTrue(source.contains("navigation.stopped(isChangingConfigurations, isFinishing)"))
        assertTrue(source.contains("onClose = ::closeMixer"))
        assertTrue(source.contains("openPermissionSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,"))
        assertFalse(source.contains("ACTION_ACCESSIBILITY_SETTINGS"))
        assertFalse(source.contains("startActivity(Intent(Settings."))
        assertFalse(source.substringAfter("override fun onStop()").substringBefore("override fun onSaveInstanceState")
            .contains("LightningDspVolumeManager"))
    }

    @Test fun retainedPolicyIgnoresSavedBackgroundMarkerDuringConfigurationRecreation() {
        val policy = VolumeManagerNavigationPolicy()
        policy.restore(returnToMain = false, externalFlow = false)
        policy.stopped(changingConfigurations = true, finishing = false)
        policy.restore(returnToMain = true, externalFlow = false)
        assertFalse(policy.returnToMain)
    }

    @Test fun newProcessHonorsSavedBackgroundAndPermissionStates() {
        val background = VolumeManagerNavigationPolicy()
        background.restore(returnToMain = true, externalFlow = false)
        assertTrue(background.returnToMain)
        val permission = VolumeManagerNavigationPolicy()
        permission.restore(returnToMain = false, externalFlow = true)
        permission.stopped(changingConfigurations = false, finishing = false)
        assertFalse(permission.returnToMain)
    }

    @Test fun leavingMixerReturnsToMainWithoutChangingManagerState() {
        val policy = VolumeManagerNavigationPolicy()
        policy.stopped(changingConfigurations = false, finishing = false)
        assertTrue(policy.returnToMain)
    }

    @Test fun recreationAndEffectNavigationDoNotRedirect() {
        val policy = VolumeManagerNavigationPolicy()
        policy.stopped(changingConfigurations = true, finishing = false)
        assertFalse(policy.returnToMain)
        policy.stopped(changingConfigurations = false, finishing = true)
        assertFalse(policy.returnToMain)
    }

    @Test fun grantedDeniedCanceledOrFailedPermissionCannotExemptTheNextHomePress() {
        listOf("granted", "denied", "canceled", "failed", "binder died", "already granted").forEach { outcome ->
            val policy = VolumeManagerNavigationPolicy()
            policy.externalFlow = true
            policy.externalFlowFinished()
            policy.stopped(changingConfigurations = false, finishing = false)
            assertTrue(outcome, policy.returnToMain)
        }
    }

    @Test fun shizukuCompletionAndCancellationAreWiredWithoutCapturingActivity() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/root/AndroidManifest.xml").isFile }
        val activity = File(app,
            "src/root/java/lightning/dsp/volumemanager/ui/LightningDspVolumeManagerActivity.kt").readText()
        val backend = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeManager.kt").readText()
            .substringAfter("fun requestShizukuPermission(").substringBefore("val perAppEnabled:")
        assertTrue(activity.contains("requestPolicy.externalFlowFinished()"))
        assertTrue(activity.contains("override fun onCleared()"))
        assertTrue(activity.contains("cancelPermissionRequest?.invoke()"))
        assertTrue(backend.contains("removeRequestPermissionResultListener(resultListener)"))
        assertTrue(backend.contains("removeBinderDeadListener(deadListener)"))
        assertTrue(backend.contains("onFinished?.invoke()"))
        assertTrue(backend.contains(".onFailure { failure ->\n                    complete()"))
    }

    @Test fun permissionRoundTripKeepsMixerButNextBackgroundDoesNot() {
        val policy = VolumeManagerNavigationPolicy()
        policy.externalFlow = true
        policy.stopped(changingConfigurations = false, finishing = false)
        assertFalse(policy.returnToMain)
        policy.externalFlow = false // onResume completes the temporary flow.
        policy.stopped(changingConfigurations = false, finishing = false)
        assertTrue(policy.returnToMain)
    }
    @Test fun persistentVolumeNotificationOpensMixerAndProcessingNotificationOpensMain() {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/root/AndroidManifest.xml").isFile }
        val service = File(app,
            "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeService.kt").readText()
        assertTrue(service.contains("Intent(this, LightningDspVolumeManagerActivity::class.java)"))
        assertTrue(service.contains("Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP"))
        assertFalse(service.contains("Intent(this, MainActivity::class.java)"))
        val helper = File(app,
            "src/main/java/lightning/dsp/utils/notifications/ServiceNotificationHelper.kt").readText()
        assertTrue(helper.contains("Intent(context, MainActivity::class.java)"))
    }

}
