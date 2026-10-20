package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.*
import org.junit.Test

class NativeControlReplyTest {
    @Test fun distinguishesDiscoveryFromReady() {
        assertFalse(NativeControlReply.parse("JDSPCTL:OK:ready=0:contexts=0")!!.ready)
        assertTrue(NativeControlReply.parse("JDSPCTL:OK:ready=1:contexts=1")!!.ready)
        assertNull(NativeControlReply.parse("unrelated log"))
    }
    @Test fun acceptanceIsNotApplication() {
        val pending = NativeControlReply.parse("JDSPCTL:OK:accepted=7:applied=6:pending=1")!!
        assertTrue(pending.accepted)
        assertFalse(pending.applied)
        assertTrue(NativeControlReply.parse("JDSPCTL:OK:accepted=7:applied=7:pending=0")!!.applied)
        assertFalse(NativeControlReply.parse("JDSPCTL:ERR:write-4")!!.accepted)
        assertFalse(NativeControlReply.parse("JDSPCTL:OK:accepted=bad:applied=bad:pending=0")!!.applied)
    }
}
