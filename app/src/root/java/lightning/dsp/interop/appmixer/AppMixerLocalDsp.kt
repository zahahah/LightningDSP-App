package lightning.dsp.interop.appmixer

import android.content.Context
import kotlinx.coroutines.runBlocking
import lightning.dsp.R
import lightning.dsp.interop.LightningDspLocalEngine
import lightning.dsp.interop.LightningDspWrapper
import lightning.dsp.interop.NativeDspHandlePolicy
import lightning.dsp.interop.PreferenceCache
import lightning.dsp.preference.FileLibraryPreference
import lightning.dsp.utils.Constants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One in-process DSP. Configuration/files stay off PCM; native JDSP serializes mutations.
 * No AudioEffect is attached to the return track, so software inserts cannot deny FAST. */
internal class AppMixerLocalDsp private constructor(
    private val context: Context,
    private val stop: AtomicBoolean,
) : AutoCloseable {
    private val engine = LightningDspLocalEngine(context, loadBenchmarks = false, publishSampleRate = false)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AppMixer-DSP-config").apply { isDaemon = true }
    }
    @Volatile private var failed = false
    private var appliedFiles = emptyList<Stamp>()
    private data class Stamp(val path: String, val modified: Long, val size: Long)

    private fun stamps(): List<Stamp> {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = (Constants.DSP_PREFERENCE_NAMESPACES + Constants.PREF_APP).map {
            File(directory, "$it.xml")
        }.toMutableList()
        for ((namespace, key) in listOf(
                Constants.PREF_CONVOLVER to R.string.key_convolver_file,
                Constants.PREF_DDC to R.string.key_ddc_file,
                Constants.PREF_LIVEPROG to R.string.key_liveprog_file)) {
            val name = PreferenceCache.getPreferences(context, namespace).getString(context.getString(key), "").orEmpty()
            if (name.isNotBlank()) files += File(FileLibraryPreference.createFullPathCompat(context, name))
        }
        return files.map { Stamp(it.path, it.lastModified(), it.length()) }
    }

    private fun powered(): Boolean = PreferenceCache.getPreferences(context, Constants.PREF_APP)
        .getBoolean(context.getString(R.string.key_powered_on), true)

    private fun prepare(quantumSamples: Int) {
        check(NativeDspHandlePolicy.isValid(engine.handle) && LightningDspWrapper.isHandleValid(engine.handle)) { "Local DSP allocation failed" }
        val vars = PreferenceCache.getPreferences(context, Constants.PREF_VAR)
        val c0 = vars.getString(context.getString(R.string.key_benchmark_c0), "").orEmpty()
            .split(';').mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
        val c1 = vars.getString(context.getString(R.string.key_benchmark_c1), "").orEmpty()
            .split(';').mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
        val expected = LightningDspWrapper.getBenchmarkSize()
        if (c0.size == expected && c1.size == expected && c0.all { it.isFinite() } && c1.all { it.isFinite() })
            LightningDspWrapper.loadBenchmark(c0, c1)
        engine.sampleRate = 48000f
        check(powered()) { "DSP was switched off before preparation" }
        var ready = false
        repeat(3) {
            if (!ready) {
                val before = stamps()
                check(runBlocking { engine.synchronizeRelayState(force = true) }) { "Local DSP preferences rejected" }
                if (before == stamps()) { appliedFiles = before; ready = true }
            }
        }
        check(ready) { "DSP settings changed repeatedly during preparation" }
        require(quantumSamples in 2..65536 && quantumSamples % 2 == 0)
        // Allocate/reconfigure native FIR state for the actual quantum before
        // routing any app audio, rather than on its first audible block.
        engine.processInt16(ShortArray(quantumSamples), ShortArray(quantumSamples), 0, quantumSamples)
        worker.scheduleWithFixedDelay({
            try {
                if (!powered()) { stop.set(true); return@scheduleWithFixedDelay }
                val current = stamps()
                if (current != appliedFiles) {
                    // File content may change while its saved preference path remains equal.
                    val externalChanged = current.drop(Constants.DSP_PREFERENCE_NAMESPACES.size + 1) !=
                        appliedFiles.drop(Constants.DSP_PREFERENCE_NAMESPACES.size + 1)
                    if (externalChanged) engine.clearCache()
                    check(runBlocking { engine.synchronizeRelayState(force = false) }) { "Local DSP update rejected" }
                    appliedFiles = current
                }
            } catch (error: Throwable) {
                android.util.Log.e("LightningDSP_FusionMode", "Local DSP update failed", error)
                failed = true
                stop.set(true)
                System.err.println("Fusion Mode relay: local DSP update failed; stopping instead of bypassing effects")
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    /** Input/output are allocated by the PCM owner, not per block. */
    fun process(input: ShortArray, output: ShortArray, count: Int) {
        check(!failed) { "Local DSP configuration failed" }
        check(count in 2..input.size && count <= output.size && count % 2 == 0)
        engine.processInt16(input, output, 0, count)
    }

    override fun close() {
        // Do not interrupt runBlocking: its native work may still be executing
        // on Dispatchers.IO. Natural completion is required before freeing JNI state.
        worker.shutdown()
        // No PCM calls remain: run() closes us only after leaving the transfer loop.
        check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "Local DSP configuration worker did not stop" }
        engine.releaseQuiescentRelay()
    }

    companion object {
        fun open(context: Context, ownerUid: Int, stop: AtomicBoolean, quantumSamples: Int, externalFiles: String): AppMixerLocalDsp {
            val fileContext = openFileContext(context, ownerUid, externalFiles)
            val dsp = AppMixerLocalDsp(fileContext, stop)
            try { dsp.prepare(quantumSamples); return dsp }
            catch (error: Throwable) { runCatching { dsp.close() }; throw error }
        }

        private fun openFileContext(context: Context, ownerUid: Int, externalFiles: String): Context {
            val name = context.packageManager.getPackagesForUid(ownerUid)?.singleOrNull()
            check(name == "com.lightning.dsp") { "Local DSP owner package is not verified" }
            val owner = context.createPackageContext(name, Context.CONTEXT_IGNORE_SECURITY)
            check(owner.applicationInfo.uid == ownerUid) { "Local DSP owner user does not match" }
            check(File(owner.applicationInfo.dataDir, "shared_prefs/${Constants.PREF_APP}.xml").canRead()) {
                "Local DSP owner preferences are unavailable"
            }
            val storage = AppMixerExternalPath.verify(externalFiles, name)
            return AppMixerRelayFileContext(owner, storage)
        }
        /** Read-only storage probe: no native allocation, script execution or audio policy. */
        fun verifyFileAccess(context: Context, ownerUid: Int, externalFiles: String) {
            val owner = openFileContext(context, ownerUid, externalFiles)
            for (namespace in Constants.DSP_PREFERENCE_NAMESPACES + Constants.PREF_APP + Constants.PREF_VAR)
                PreferenceCache.getPreferences(owner, namespace).all
            for ((namespace, key) in listOf(
                    Constants.PREF_CONVOLVER to R.string.key_convolver_file,
                    Constants.PREF_DDC to R.string.key_ddc_file,
                    Constants.PREF_LIVEPROG to R.string.key_liveprog_file)) {
                val path = PreferenceCache.getPreferences(owner, namespace)
                    .getString(owner.getString(key), "").orEmpty()
                FileLibraryPreference.createFullPathCompat(owner, path)
            }
        }
    }
}
