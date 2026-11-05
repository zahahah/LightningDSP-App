package lightning.dsp.utils

import org.junit.Assert.*
import org.junit.Test

class RoutingDeviceIdentityTest {
    private val speaker = RoutingDeviceIdentity("Phone speaker", "", "SPEAKER")

    @Test fun recreatedSnapshotOfTheSameDeviceIsNotARouteChange() {
        assertNotSame(speaker, speaker.copy())
        assertEquals(speaker, speaker.copy())
        assertEquals(speaker.hashCode(), speaker.copy().hashCode())
    }

    @Test fun actualRouteMetadataChangesRemainDistinct() {
        assertNotEquals(speaker, speaker.copy(group = "ANALOG"))
        assertNotEquals(speaker, speaker.copy(productName = "USB DAC"))
        val bluetooth = RoutingDeviceIdentity("Headphones", "00:11", "BLUETOOTH")
        assertNotEquals(bluetooth, bluetooth.copy(address = "00:22"))
    }

    @Test fun initialSelectionDisconnectAndReconnectStillChangeIdentity() {
        var previous: RoutingDeviceIdentity? = null
        val changes = mutableListOf<RoutingDeviceIdentity?>()
        fun refresh(current: RoutingDeviceIdentity?) {
            if (previous != current) changes += current
            previous = current
        }

        refresh(speaker)
        repeat(20) { refresh(speaker.copy()) }
        refresh(null)
        refresh(null)
        refresh(speaker.copy())
        assertEquals(listOf(speaker, null, speaker), changes)
    }
}
