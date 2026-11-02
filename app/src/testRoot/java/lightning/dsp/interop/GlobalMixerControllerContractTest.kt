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
    @Test fun unknownInspectionRetriesAreBoundedAndResetOnlyExplicitly() {
        val policy = SupplementalInspectionRetryPolicy()
        repeat(3) { assertTrue(policy.retry(true)) }
        repeat(20) { assertFalse(policy.retry(true)) }
        policy.reset()
        assertFalse(policy.retry(false))
        repeat(3) { assertTrue(policy.retry(true)) }
        assertFalse(policy.retry(true))
    }

    @Test fun unknownSnapshotsRetireOwnedEffectsAndRetryWithoutRevisionChanges() {
        val s = controller
        val unknown = s.substringAfter("private suspend fun unknownSnapshot()").substringBefore("// Fixed category")
        assertTrue(unknown.indexOf("removeAll()") < unknown.indexOf("inspectionRetries.retry"))
        assertTrue(unknown.contains("releaseLeaseIfSafe()"))
        assertTrue(unknown.contains("!closed && shouldRun()"))
        assertTrue(unknown.contains("SystemClock.elapsedRealtime() + 2_000"))
        assertFalse(unknown.contains("requestCheck()"))
        assertFalse(unknown.contains("revision++"))
        val external = s.substringAfter("fun requestCheck()").substringBefore("fun preferencesChanged()")
        assertTrue(external.contains("inspectionRetries.reset()"))
        val internal = s.substringAfter("private fun requestInternalCheck()").substringBefore("private suspend fun unknownSnapshot")
        assertFalse(internal.contains("inspectionRetries.reset"))
        assertFalse(internal.contains("revision++"))
        assertTrue(internal.contains("!closed && poll"))
        assertTrue(s.contains("delay(maxOf(200L, retryNotBefore - SystemClock.elapsedRealtime()))"))
        assertEquals(5, Regex("unknownSnapshot\\(\\)").findAll(s).count() - 1)
    }

    @Test fun diagnosticsAreRateLimitedAndDistinguishCollectionAndEligibilityFailures() {
        val s = controller
        listOf("root_unavailable", "missing_timeout", "missing_head", "dump_failed_or_timed_out",
            "dump_truncated", "dump_completion_marker_missing", "parser_unknown", "unstable_snapshot",
            "no_candidates", "barrier_failed", "fence_failed", "preparation_failed",
            "activation_failed", "verification_failed", "owned_removed", "inspection_exhausted").forEach {
            assertTrue(it, s.contains("\"$it\""))
        }
        assertTrue(s.contains("now - previous < 15_000"))
        assertTrue(s.contains("parser rejected snapshot: \${it.reason}"))
        assertFalse(s.contains("Timber.w(failure"))
        assertFalse(s.contains("Timber.i(flinger"))
        assertFalse(s.contains("Timber.i(policy"))
    }

    @Test fun unexpectedFailuresReportOnlySafeTypeAndFixedPhase() {
        val s = controller
        val caught = s.substringAfter("catch (failure: Exception)").substringBefore("requestCheck()")
        assertTrue(caught.contains("failure.javaClass.simpleName"))
        assertTrue(caught.contains("diagnose(\"controller_exception\""))
        assertTrue(caught.contains("phase=$" + "diagnosticPhase type=$" + "type"))
        assertFalse(caught.contains("failure.message"))
        assertFalse(caught.contains("failure.stackTrace"))
        assertFalse(caught.contains("Timber.w(failure"))
        listOf("inspection", "construction", "preparation", "activation", "verification",
            "teardown", "lease_barrier", "legacy_fence", "lease_release").forEach {
            assertTrue(it, s.contains("diagnosticPhase = \"$it\""))
        }
    }

}
