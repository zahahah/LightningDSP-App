package lightning.dsp.utils

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class RoutingObserverStartupTest {
    private fun source(path: String): String {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
        return File(app, path).readText()
    }

    @Test
    fun firstRouteObserverReconcilesAnAlreadySelectedDevice() {
        val observer = source("src/main/java/lightning/dsp/utils/RoutingObserver.kt")
        val register = observer.substringAfter("fun registerOnRoutingChangeListener")
            .substringBefore("fun unregisterOnRoutingChangeListener")

        assertTrue(register.contains("router.addCallback("))
        assertTrue(register.contains("refreshSelectedRoute()"))
        assertTrue(register.contains("audioManager.registerAudioDeviceCallback"))
        assertTrue(observer.contains("router.selectedRoute.findOutputDevice()"))
    }

    @Test
    fun routeChangesAndBackgroundServiceStartupReconcileProfiles() {
        val observer = source("src/main/java/lightning/dsp/utils/RoutingObserver.kt")
        val service = source("src/main/java/lightning/dsp/service/RootAudioProcessorService.kt")

        assertTrue(observer.contains("override fun onAudioDevicesAdded"))
        assertTrue(observer.contains("override fun onAudioDevicesRemoved"))
        assertTrue(service.contains("app.profileManager.refreshCurrentDevice()"))
    }

    @Test
    fun refreshNotifiesOnlyWhenDeviceMetadataChanges() {
        val observer = source("src/main/java/lightning/dsp/utils/RoutingObserver.kt")
        val observable = observer.substringAfter("var currentDevice by Delegates.observable")
            .substringBefore("private val AudioDeviceInfo.deviceGroup")
        assertTrue(observable.contains("old?.routeIdentity != new?.routeIdentity"))
        assertFalse(observable.contains("if (old != new)"))
        assertTrue(observer.contains("productName, address, group.name"))
    }

    @Test
    fun explicitRetriggerAndInitialDeliveryAreNotDeduplicatedAway() {
        val observer = source("src/main/java/lightning/dsp/utils/RoutingObserver.kt")
        val retrigger = observer.substringAfter("fun retrigger()")
            .substringBefore("fun refreshSelectedRoute()")
        val register = observer.substringAfter("fun registerOnRoutingChangeListener")
            .substringBefore("fun unregisterOnRoutingChangeListener")
        assertTrue(retrigger.contains("currentDevice?.let(callback::onRoutingDeviceChanged)"))
        assertTrue(register.contains("currentDevice?.let(callback::onRoutingDeviceChanged)"))
        assertTrue(observer.contains("router.selectedRoute.findOutputDevice()"))
    }
}
