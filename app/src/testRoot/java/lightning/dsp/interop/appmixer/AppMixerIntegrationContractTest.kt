package lightning.dsp.interop.appmixer

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cross-file guards complement fake-process tests; they do not prove device audio. */
class AppMixerIntegrationContractTest {
    private fun read(path: String) = sequenceOf(File("src/$path"), File("app/src/$path"))
        .first { it.isFile }.readText()

    @Test fun serviceRetiresOtherOwnersBeforeAcquiringRelaySessionLease() {
        val source = read("main/java/lightning/dsp/service/RootAudioProcessorService.kt")
        val acquire = source.substringAfter("private fun acquireMixerExclusive()")
            .substringBefore("private fun updateServiceNotification()")
        assertTrue(acquire.indexOf("it?.close()") < acquire.indexOf("retiring.awaitClosed(8_000)"))
        assertTrue(acquire.indexOf("it?.close()") < acquire.indexOf("acquireMixerSessionLease()"))
        assertTrue(acquire.indexOf("acquireMixerSessionLease()") < acquire.indexOf("retiring.awaitClosed(8_000)"))
        assertTrue(acquire.indexOf("retiring.awaitClosed(8_000)") < acquire.indexOf("retired.any { !it.isClosed() }"))
        assertTrue(acquire.contains("uncertainMixerLease = lease"))
        assertTrue(source.contains("else if (app.rootSessionDatabase.isMixerSessionLeaseHeld())"))
        assertTrue(source.contains("Previous App Mixer cleanup is pending or uncertain"))
    }

    @Test fun thirdModeSharesExistingAppPowerAndExcludesNormalEngineAdmission() {
        val settings = read("main/java/lightning/dsp/fragment/settings/SettingsAudioFormatFragment.kt")
        val database = read("main/java/lightning/dsp/session/root/RootSessionDatabase.kt")
        val inventory = read("root/java/lightning/dsp/interop/appmixer/AppMixerUidInventory.kt")
        assertTrue(settings.contains(".putBoolean(mixerKey, mixerEnabled).apply()"))
        assertTrue(settings.contains("sessionMode?.isChecked = !globalEnabled && !mixerEnabled"))
        assertTrue(database.contains("!app.isAppMixerMode && id !in supplementalSessionReservations"))
        assertTrue(database.contains("!app.isAppMixerMode && (id <= 0 ||"))
        assertTrue(inventory.contains("SESSION_DSP_ENABLED_PREFIX + name, true"))
    }

    @Test fun persistedPoweredAppMixerModeStartsProcessorWhenMainActivityOpens() {
        val activity = read("main/java/lightning/dsp/activity/MainActivity.kt")
        assertTrue(activity.contains("app.isEnhancedProcessing ||"))
        assertTrue(activity.contains("app.isAppMixerMode && prefsApp.get(R.string.key_powered_on)"))
        assertTrue(activity.contains("RootAudioProcessorService.startServiceEnhanced(this)"))
    }

    @Test fun relayAcknowledgementDoesNotContainUnboundedUidList() {
        val helper = read("root/java/lightning/dsp/interop/appmixer/AppMixerRoutingExperiment.kt")
        val acknowledgement = helper.lineSequence().first { it.contains("APP_MIXER:RUNNING session=") }
        assertTrue(acknowledgement.contains("uidCount="))
        assertFalse(acknowledgement.contains("joinToString"))
        assertTrue(helper.contains("kotlin.system.exitProcess(exitCode)"))
    }

    @Test fun supervisedRouteDoesNotPeriodicallyExpireOrRestart() {
        val controller = read("root/java/lightning/dsp/interop/appmixer/AppMixerModeController.kt")
        val helper = read("root/java/lightning/dsp/interop/appmixer/AppMixerRoutingExperiment.kt")
        assertFalse(controller.contains("reconcile(true)"))
        assertTrue(helper.contains("if (prepareExternalEffect == null)"))
        assertTrue(helper.contains("else Long.MAX_VALUE"))
    }
}
