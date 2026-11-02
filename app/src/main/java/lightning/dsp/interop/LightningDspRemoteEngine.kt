package lightning.dsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import dev.rikka.tools.refine.Refine
import lightning.dsp.MainApplication
import lightning.dsp.interop.structure.EelVmVariable
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.AudioEffectExtensions.getParameterInt
import lightning.dsp.utils.extensions.AudioEffectExtensions.setParameter
import lightning.dsp.utils.extensions.AudioEffectExtensions.setParameterCharBuffer
import lightning.dsp.utils.extensions.AudioEffectExtensions.setParameterFloatArray
import lightning.dsp.utils.extensions.AudioEffectExtensions.setParameterImpulseResponseBuffer
import lightning.dsp.utils.extensions.ContextExtensions.registerLocalReceiver
import lightning.dsp.utils.extensions.ContextExtensions.showAlert
import lightning.dsp.utils.extensions.ContextExtensions.toast
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import lightning.dsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import lightning.dsp.utils.extensions.crc
import lightning.dsp.utils.extensions.toShort
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

class LightningDspRemoteEngine(
    context: Context,
    val sessionId: Int,
    val priority: Int,
    callbacks: LightningDspWrapper.LightningDspCallbacks? = null,
    private val supplemental: Boolean = false,
    private val onSupplementalInvalidated: (() -> Unit)? = null,
) : LightningDspBaseEngine(context, callbacks, publishSampleRate = !supplemental) {

    private val usesUnscopedNative: Boolean
        get() = EngineScopePolicy.usesUnscopedNative(sessionId, supplemental)
    private val nativeReady: Boolean
        get() = usesUnscopedNative && LightningDspControlClient.isAvailable()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (supplemental && !supplementalAdmitted) return
            if (supplemental && intent.action in arrayOf(
                    Constants.ACTION_SERVICE_HARD_REBOOT_CORE, Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)) {
                invalidateSupplemental()
                return
            }
            when (intent.action) {
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_PRESET_LOADED -> PresetPreferenceSyncPolicy
                    .forceUpdateNamespacesFor(intent.action)
                    ?.let(::syncWithPreferences)
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> rebootEngine()
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { clearCache(); syncWithPreferences() }
            }
        }
    }

    var effect: AudioEffectHidden? = createEffect()
    @Volatile private var disposed = false
    private val supplementalState = SupplementalStateFence(
        canUseEndpoint = { supplemental && sessionId > 0 &&
            LightningDspControlClient.isCoverageLeaseActive() && canControlEndpoint() },
        isEnabled = { enabled },
        writeCompleteState = { syncCompleteState(it) },
        enable = { enabled = true },
    )
    private val supplementalAdmitted: Boolean get() = !supplemental || supplementalState.admitted
    private val preparedGeneration: Long get() = supplementalState.preparedGeneration
    @Volatile private var supplementalInvalidated = false
    private var requestedEnabled = false

    override var enabled: Boolean
        set(value) {
            if (disposed || (supplemental && value &&
                    (!supplementalAdmitted || preparedGeneration != DspDesiredState.current() ||
                        !LightningDspControlClient.isCoverageLeaseActive() || !canControlEndpoint()))) return
            requestedEnabled = value
            // The framework must dispatch audio even when the global control
            // service owns the DSP parameter. Binder acceptance alone does not
            // enable the AudioFlinger effect or its processing thread.
            try { if (!supplemental || canControlEndpoint()) effect?.enabled = value }
            catch (e: Exception) { Timber.w(e, "Framework effect enable failed; native diagnostics retained") }
            if (usesUnscopedNative) LightningDspControlClient.setEnabledIfSupported(value)
        }
        get() = runCatching { effect?.enabled ?: false }.getOrDefault(false)

    override var sampleRate: Float
        get() {
            super.sampleRate = effect.getParameterInt(20001)?.toFloat() ?: -0f
            return super.sampleRate
        }
        set(_){}

    init {
        watchControl(effect)
        if (usesUnscopedNative) LightningDspControlClient.probeAsync { nativeControlReady ->
            if (nativeControlReady && !disposed) syncWithPreferences(arrayOf(Constants.PREF_OUTPUT, Constants.PREF_EQ))
        }
        if (!supplemental) syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(Constants.ACTION_PREFERENCES_UPDATED)
        filter.addAction(Constants.ACTION_PRESET_LOADED)
        filter.addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
    }

    private fun createEffect(): AudioEffectHidden? {
        if (supplemental && sessionId <= 0) return null
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_LIGHTNINGDSP, priority, sessionId)
        } catch (e: Exception) {
            Timber.w(e, "AudioEffect attachment unavailable; keep global-control diagnostics alive")
            null
        }
    }

    private fun checkEngine() {
        val attached = effect ?: return
        // Idle audio is not a dead context. Check the framework endpoint itself,
        // independently of Binder publication and sample-rate/frame telemetry.
        if (runCatching { Refine.unsafeCast<AudioEffect>(attached).enabled }.isFailure) {
            if (supplemental) {
                invalidateSupplemental()
                return
            }
            Timber.w("AudioEffect endpoint invalid for session %s; recreating", sessionId)
            rebootEngine()
        }
    }

    private fun watchControl(attached: AudioEffectHidden?) {
        attached ?: return
        Refine.unsafeCast<AudioEffect>(attached).setControlStatusListener { _, granted ->
            if (disposed || effect !== attached) return@setControlStatusListener
            if (supplemental) {
                if (!granted) invalidateSupplemental()
                return@setControlStatusListener
            }
            // Invalidate queued work for the old ownership epoch, then let every
            // writable session reconcile. A session without control waits for gain.
            DspDesiredState.replace { }
            context.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
            if (granted) enabled = requestedEnabled
        }
    }

    private fun rebootEngine() {
        if (supplemental) {
            if (supplementalAdmitted) invalidateSupplemental()
            return
        }
        DspDesiredState.replace {
            if (disposed) return@replace
            if (usesUnscopedNative) LightningDspControlClient.invalidateContext()
            effect?.release()
            effect = createEffect()
            watchControl(effect)
        }
        context.sendLocalBroadcast(Intent(Constants.ACTION_PRESET_LOADED))
        enabled = requestedEnabled
        if (usesUnscopedNative) LightningDspControlClient.probeAsync()
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        if (disposed || (supplemental && !supplementalAdmitted)) return
        if(effect == null && !nativeReady) {
            Timber.d("Rejecting update due to disposed engine")
            return
        }

        checkEngine()
        val attached = effect
        if (attached != null && !runCatching {
                Refine.unsafeCast<AudioEffect>(attached).hasControl()
            }.getOrDefault(false)) return
        super.syncWithPreferences(forceUpdateNamespaces)
    }

    override fun preferenceUpdateOrder(targets: Array<String>) =
        orderNativePreferenceUpdates(targets, nativeReady)

    override fun canApplyPreferenceState(): Boolean =
        !disposed && (!supplemental || supplementalAdmitted) &&
            (if (effect == null) nativeReady else canControlEndpoint())

    internal fun effectIdOrNull(): Int? = effect?.let { attached ->
        if (disposed) null else runCatching { Refine.unsafeCast<AudioEffect>(attached).id }.getOrNull()
    }

    internal fun canControlEndpoint(): Boolean = !disposed && !supplementalInvalidated &&
        (effect?.let { runCatching { Refine.unsafeCast<AudioEffect>(it).hasControl() }.getOrDefault(false) } == true)

    internal fun isClosed(): Boolean = disposed

    /** Called only after the coverage lease's worker barrier. Completing every
     * legacy namespace also supersedes any previously accepted native update. */
    internal suspend fun prepareLegacyState(expectedGeneration: Long): Boolean {
        if (supplemental || sessionId != 0 || !LightningDspControlClient.isCoverageLeaseActive() ||
            !canControlEndpoint()) return false
        return syncCompleteState(expectedGeneration) && canControlEndpoint() &&
            LightningDspControlClient.isCoverageLeaseActive() &&
            DspDesiredState.current() == expectedGeneration
    }

    /** Construction is deliberately inert. The controller must first prove a
     * fresh owned effect ID and current target, then prepare while disabled. */
    internal suspend fun prepareSupplemental(expectedGeneration: Long): Boolean {
        if (!supplemental) return false
        return supplementalState.prepare(expectedGeneration)
    }

    internal fun activateSupplemental(expectedGeneration: Long): Boolean =
        supplemental && supplementalState.activate(expectedGeneration)

    private fun invalidateSupplemental() = DspDesiredState.serialized {
        if (supplementalInvalidated || disposed) return@serialized
        val hadControl = canControlEndpoint()
        supplementalInvalidated = true
        supplementalState.invalidate()
        requestedEnabled = false
        if (hadControl) runCatching { effect?.enabled = false }
        runCatching { onSupplementalInvalidated?.invoke() }
            .onFailure { Timber.w(it, "Supplemental retirement callback failed") }
    }

    override fun close() {
        DspDesiredState.serialized {
            if (disposed) return@serialized
            disposed = true
            supplementalState.invalidate()
            context.unregisterLocalReceiver(broadcastReceiver)
            if (usesUnscopedNative) LightningDspControlClient.invalidateContext()
            effect?.release()
            effect = null
            super.close()
        }
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        // Limiter threshold/release still use the legacy path. Apply those
        // first so they cannot immediately cancel the queued native post-gain.
        val limiterApplied = effect.setParameterFloatArray(
            1500,
            floatArrayOf(threshold, release, postGain)
        ) == AudioEffect.SUCCESS
        val nativeQueued = usesUnscopedNative && LightningDspControlClient.setPostGainIfSupported(postGain) {
            if (!disposed) effect.setParameterFloatArray(1500, floatArrayOf(threshold, release, postGain))
        }
        return limiterApplied || nativeQueued
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return (effect.setParameterFloatArray(
            115,
            floatArrayOf(timeConstant, granularity.toFloat(), tfTransforms.toFloat()) + bands.map { it.toFloat() }
        ) == AudioEffect.SUCCESS) and (effect.setParameter(1200, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(128, preset.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1203, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(188, mode.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1208, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean {
        throw UnsupportedOperationException()
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(112, maxGain.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1201, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(137, level.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1204, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(150, (level * 1000).roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1206, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray,
    ): Boolean {
        if (usesUnscopedNative && filterType == 0 && bands.size == 30 && LightningDspControlClient.setFirEqIfSupported(
                FloatArray(15) { bands[it].toFloat() },
                FloatArray(15) { bands[it + 15].toFloat() },
                if (interpolationMode == 1) 1 else 0,
                enable,
                fallback = {
                    if (!disposed) applyLegacyMultiEqualizer(enable, filterType, interpolationMode, bands)
                },
            )) return true
        return applyLegacyMultiEqualizer(enable, filterType, interpolationMode, bands)
    }

    private fun applyLegacyMultiEqualizer(
        enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray,
    ): Boolean {
        var ret = true

        if (enable) {
            val properties = floatArrayOf(
                filterType.toFloat(),
                if(interpolationMode == 1) 1.0f else -1.0f
            ) + bands.map { it.toFloat() }
            ret = effect.setParameterFloatArray(116, properties) == AudioEffect.SUCCESS
        }

        return ret and (effect.setParameter(1202, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        val prevCrc = this.ddcHash
        val currentCrc = vdc.crc()

        Timber.i("VDC hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            if (effect.setParameterCharBuffer(12001, 10009, vdc) != AudioEffect.SUCCESS) return false
            if (effect.setParameter(25001, currentCrc) != AudioEffect.SUCCESS) return false // Commit hash
        }

        return effect.setParameter(1212, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {

        val prevCrc = this.convolverHash

        Timber.i("Convolver hash before: $prevCrc, current: $irCrc")
        if (prevCrc != irCrc && enable) {
            if (effect.setParameterImpulseResponseBuffer(12000, 10004, impulseResponse, irChannels) != AudioEffect.SUCCESS) return false
            if (effect.setParameter(25003, irCrc) != AudioEffect.SUCCESS) return false // Commit hash
        }

        return effect.setParameter(1205, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        val prevCrc = this.graphicEqHash
        val currentCrc = bands.crc()

        Timber.i("GraphicEQ hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            if (effect.setParameterCharBuffer(12001, 10006, bands) != AudioEffect.SUCCESS) return false
            if (effect.setParameter(25000, currentCrc) != AudioEffect.SUCCESS) return false // Commit hash
        }

        return effect.setParameter(1210, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        val prevCrc = this.liveprogHash
        val currentCrc = script.crc()

        Timber.i("Liveprog hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            if (effect.setParameterCharBuffer(12001, 10010, script) != AudioEffect.SUCCESS) return false
            if (effect.setParameter(25002, currentCrc) != AudioEffect.SUCCESS) return false // Commit hash
        }

        return effect.setParameter(1213, enable.toShort()) == AudioEffect.SUCCESS
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return false }
    override fun supportsCustomCrossfeed(): Boolean { return false }

    // EEL VM utilities (unavailable)
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> { return arrayListOf() }
    override fun manipulateEelVariable(name: String, value: Float): Boolean { return false }
    override fun freezeLiveprogExecution(freeze: Boolean) {}

    // Status
    val pid: Int
        get() = effect.getParameterInt(20002) ?: -1
    val isPidValid: Boolean
        get() = pid > 0
    val isSampleRateAbnormal: Boolean
        get() = sampleRate <= 0
    val paramCommitCount: Int
        get() = effect.getParameterInt(19998) ?: -1
    val isPresetInitialized: Boolean
        get() = paramCommitCount > 0
    val bufferLength: Int
        get() = effect.getParameterInt(19999) ?: -1
    val allocatedBlockLength: Int
        get() = effect.getParameterInt(20000) ?: -1
    val graphicEqHash: Int
        get() = effect.getParameterInt(30000) ?: -1
    val ddcHash: Int
        get() = effect.getParameterInt(30001) ?: -1
    val liveprogHash: Int
        get() = effect.getParameterInt(30002) ?: -1
    val convolverHash: Int
        get() = effect.getParameterInt(30003) ?: -1

    enum class PluginState {
        Unavailable,
        Available,
        Unsupported
    }

    companion object {
        private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
        private val EFFECT_LIGHTNINGDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

        /** A mounted library is not enough: the audio factory must advertise it. */
        fun discoveredPluginState(): PluginState = try {
            AudioEffect.queryEffects()
                ?.firstOrNull { it.uuid == EFFECT_LIGHTNINGDSP }
                ?.run { if (name.contains("v3")) PluginState.Unsupported else PluginState.Available }
                ?: PluginState.Unavailable
        } catch (e: Exception) {
            Timber.w(e, "Unable to query the audio effect factory")
            PluginState.Unavailable
        }

        fun isPluginInstalled(): PluginState {
            // Global control is the primary root backend. Do not even query
            // AudioEffect or the legacy library unless the native probe failed.
            return resolvePluginAvailability(LightningDspControlClient.isAvailable()) {
                try {
                    // AudioEffect.queryEffects() can return null on vendor HALs
                    // that expose no descriptors yet. Treat it as empty so the
                    // filesystem fallback can run instead of crashing the UI.
                    val queriedEffects = AudioEffect.queryEffects() ?: emptyArray()
                    queriedEffects
                        .firstOrNull { it.uuid == EFFECT_LIGHTNINGDSP }
                        ?.run {
                            if(name.contains("v3")) PluginState.Unsupported else PluginState.Available
                        } ?: if (isPluginLibraryPresent()) PluginState.Available else PluginState.Unavailable
                } catch (e: Exception) {
                    Timber.e("isPluginInstalled: exception raised")
                    Timber.e(e)
                    MainApplication.instance.showAlert(
                        "Error while checking audio effect status",
                        "Unexpected error while checking whether LightningDSP's audio effect library is installed. \n\n" +
                                "Error: $e",
                    )
                    PluginState.Unavailable
                }
            }
        }

        internal fun resolvePluginAvailability(
            nativeControlReady: Boolean,
            checkLegacy: () -> PluginState,
        ): PluginState = if (nativeControlReady) PluginState.Available else checkLegacy()

        private fun isPluginLibraryPresent(): Boolean {
            return listOf(
                "/vendor/lib64/soundfx/lightningdsplib.so",
                "/vendor/lib/soundfx/lightningdsplib.so",
                "/odm/lib64/soundfx/lightningdsplib.so",
                "/odm/lib/soundfx/lightningdsplib.so",
                "/system/lib64/soundfx/lightningdsplib.so",
                "/system/lib/soundfx/lightningdsplib.so",
            ).any { java.io.File(it).isFile }
        }
    }
}
