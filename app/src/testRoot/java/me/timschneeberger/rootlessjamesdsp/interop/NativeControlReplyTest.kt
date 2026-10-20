package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.*
import org.junit.Test

class NativeControlReplyTest {
    @Test fun distinguishesDiscoveryFromReady() {
        assertFalse(NativeControlReply.parse("JDSPCTL:OK:ready=0:contexts=0")!!.ready)
        assertFalse(NativeControlReply.parse("JDSPCTL:OK:ready=1:contexts=1")!!.ready)
        val valid = "JDSPCTL:OK:ready=1:protocol=3:contexts=1:writable=1:publication=3"
        assertTrue(NativeControlReply.parse(valid)!!.ready)
        assertFalse(NativeControlReply.parse(valid.replace("protocol=3", "protocol=4"))!!.ready)
        assertFalse(NativeControlReply.parse(valid.replace("publication=3", "publication=5"))!!.ready)
        assertFalse(NativeControlReply.parse(valid.replace("writable=1", "writable=0"))!!.ready)
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
