package lightning.dsp.interop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guard only: real Binder death/identity behavior still requires a device. */
class UnifiedSessionOwnerProtocolTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
    @Test fun ownershipUsesRealBinderCallerAndBoundedOneWayChallenge() {
        val proof = File(app, "src/root/java/lightning/dsp/interop/UnifiedSessionOwnerProof.kt").readText()
        val provider = File(app, "src/root/java/lightning/dsp/interop/CompatibilityPreparationProvider.kt").readText()
        val hook = File(app, "src/root/java/lightning/dsp/interop/AudioCompatibilityHook.java").readText()
        assertTrue(proof.contains("UnifiedSessionOwnerAdmission.matches(uid, pid, getCallingUid(), getCallingPid())"))
        assertTrue(proof.contains("token.linkToDeath(death, 0)"))
        assertTrue(proof.contains("IBinder.FLAG_ONEWAY"))
        assertTrue(proof.contains("if (flags and IBinder.FLAG_ONEWAY != 0) return false"))
        assertTrue(hook.contains("ack.transact(OWNER_CODE, confirmation, response, 0)"))
        assertTrue(!hook.contains("ack.transact(OWNER_CODE, confirmation, null, android.os.IBinder.FLAG_ONEWAY)"))
        assertTrue(proof.contains("minOf(400L"))
        assertTrue(proof.contains("+ 6_000"))
        assertTrue(proof.contains("dead.set(true); onRevoked()"))
        val database = File(app, "src/main/java/lightning/dsp/session/root/RootSessionDatabase.kt").readText()
        assertTrue(database.contains("Handler(android.os.Looper.getMainLooper())"))
        assertTrue(database.contains("if (!retentionLifecycle.accepts(generation)) return@post"))
        assertTrue(database.contains("retentionLifecycle.current() != null && !app.isGlobalMode"))
        val service = File(app, "src/main/java/lightning/dsp/service/RootAudioProcessorService.kt").readText()
        val teardown = service.substringAfter("override fun onDestroy()")
        assertTrue(teardown.indexOf("stopUnifiedSessionRetention()") < teardown.indexOf("clearSessions()"))
        val stop = database.substringAfter("fun stopUnifiedSessionRetention()").substringBefore("fun registerUnifiedSession")
        assertTrue(stop.contains("unifiedSessions.clear()"))
        assertTrue(!stop.contains("applyPlaybackActivity()"))
        assertTrue(provider.indexOf("val handler = CompatibilityPreparationBridge.handler") <
            provider.indexOf("registerUnifiedSession(session"))
        assertTrue(database.contains("unifiedSessions.reconcile(retainEnabled)"))
        assertTrue(database.contains("unifiedFocus.reconcile(retainEnabled)"))
        assertTrue(database.contains("if (unifiedSessions.hasClaims() || unifiedFocus.hasOwners())"))
        val option = File(app, "src/root/java/lightning/dsp/interop/AAudioSessionReuseOption.kt").readText()
        assertTrue(option.contains("?.rootSessionDatabase?.reconcileUnifiedSessionRetention()"))
        assertTrue(provider.contains("val pid = Binder.getCallingPid()"))
        assertTrue(provider.contains("extras?.getBoolean(\"unified\", false) == true && !app.isGlobalMode"))
        assertTrue(provider.contains("if (method == \"retain\") return result(true)"))
        assertTrue(hook.contains("if (pending != null && !pending.isDone()) return"))
        assertTrue(hook.contains("}, 2, 2, TimeUnit.SECONDS)"))
    }
}
