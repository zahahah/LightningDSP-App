package lightning.dsp.interop

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-bound guards complement parser tests; not Android lifecycle/runtime proof. */
class GlobalMixerControllerContractTest {
    private fun source(path: String): String = sequenceOf(File("src/$path"), File("app/src/$path"))
        .first { it.isFile }.readText()
    private val controller get() = source("root/java/lightning/dsp/interop/GlobalMixerFallbackController.kt")

    @Test fun quiescenceAndGlobalFencePrecedeSupplementalConstruction() {
        val s = controller
        assertTrue(s.indexOf("ensureLeaseAndFence(global, expected)") < s.indexOf("LightningDspRemoteEngine(app, target.id"))
        val fence = s.substringAfter("private suspend fun ensureLeaseAndFence")
        assertTrue(fence.indexOf("barrier?.await()") < fence.indexOf("global.prepareLegacyState(generation)"))
        assertTrue(fence.indexOf("global.prepareLegacyState(generation)") < fence.indexOf("leaseFenced = true"))
    }
    @Test fun identityThenFullStateThenFreshSnapshotPrecedeActivation() {
        val s = controller
        assertTrue(s.indexOf("before.effects.any { it.id == id }") < s.indexOf("engine.prepareSupplemental(generation)"))
        assertTrue(s.indexOf("engine.prepareSupplemental(generation)") < s.indexOf("val fresh = stableSnapshot(global)"))
        assertTrue(s.indexOf("fresh.ownsAttachment(target, id)") < s.indexOf("engine.activateSupplemental(generation)"))
        assertTrue(s.contains("DspDesiredState.current() != generation"))
    }
    @Test fun withdrawalPrecedesReplacementAndTeardownFencesEveryLiveBaseline() {
        val s = controller
        assertTrue(s.indexOf("remove(obsolete)") < s.indexOf("for (target in targets.values)"))
        val release = s.substringAfter("private suspend fun releaseLeaseIfSafe").substringBefore("private fun remove")
        assertTrue(release.contains("SupplementalLifecycle.releaseIfSafe("))
        assertTrue(release.contains("original = leaseBaseline"))
        assertTrue(release.contains("current = ::baseline"))
        assertTrue(release.contains("endpoint.prepareLegacyState(generation)"))
        assertTrue(release.contains("release = LightningDspControlClient::releaseCoverageLease"))
        assertTrue(release.contains("owned.isNotEmpty()"))
    }
    @Test fun inspectionUsesIoDispatcherAndBoundedConstantServiceDumps() {
        val s = controller
        assertTrue(s.contains("withContext(Dispatchers.IO)"))
        assertTrue(s.contains("readDump(\"media.audio_flinger\")"))
        assertTrue(s.contains("readDump(\"media.audio_policy\")"))
        assertTrue(s.contains("/system/bin/timeout 5 /system/bin/dumpsys"))
        assertTrue(s.contains("LDSP_MIXER_READ_COMPLETE"))
        assertFalse(s.contains("audioserver"))
    }
    @Test fun featureIsServiceOwnedRootOnlyAndIndependentOfVolumeManager() {
        val service = source("main/java/lightning/dsp/service/RootAudioProcessorService.kt")
        assertTrue(service.contains("if (isRoot()) {\n            mixerFallback = GlobalMixerFallbackController"))
        assertTrue(service.contains("mixerFallback?.close()"))
        assertTrue(service.contains("preferences.get<Boolean>(R.string.key_audioformat_all_mixers)"))
        listOf("rootless", "plugin").forEach { flavor ->
            val noop = source("$flavor/java/lightning/dsp/interop/GlobalMixerFallbackController.kt")
            assertFalse(noop.contains("LightningDspRemoteEngine("))
            assertFalse(noop.contains("RootShellImpl"))
        }
    }
}
