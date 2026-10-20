package me.timschneeberger.rootlessjamesdsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.getParameterInt
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameter
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterCharBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterFloatArray
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterImpulseResponseBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.crc
import me.timschneeberger.rootlessjamesdsp.utils.extensions.toShort
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

class JamesDspRemoteEngine(
    context: Context,
    val sessionId: Int,
    val priority: Int,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null,
) : JamesDspBaseEngine(context, callbacks) {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> rebootEngine()
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { clearCache(); syncWithPreferences() }
            }
        }
    }

    var effect: AudioEffectHidden? = createEffect()
    @Volatile private var disposed = false

    override var enabled: Boolean
        set(value) {
            // The framework must dispatch audio even when the global control
            // service owns the DSP parameter. Binder acceptance alone does not
            // enable the AudioFlinger effect or its processing thread.
            try { effect?.enabled = value }
            catch (e: Exception) { Timber.w(e, "Framework effect enable failed; native diagnostics retained") }
            JamesDspControlClient.setEnabledIfSupported(value)
        }
        get() = effect?.enabled ?: false

    override var sampleRate: Float
        get() {
            super.sampleRate = effect.getParameterInt(20001)?.toFloat() ?: -0f
            return super.sampleRate
        }
        set(_){}

    init {
        JamesDspControlClient.probeAsync { nativeControlReady ->
            if (nativeControlReady && !disposed) syncWithPreferences(arrayOf(Constants.PREF_OUTPUT, Constants.PREF_EQ))
        }
        syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(Constants.ACTION_PREFERENCES_UPDATED)
        filter.addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
    }

    private fun createEffect(): AudioEffectHidden? {
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId)
        } catch (e: Exception) {
            Timber.w(e, "AudioEffect attachment unavailable; keep global-control diagnostics alive")
            null
        }
    }

    private fun checkEngine() {
        if (effect == null || JamesDspControlClient.isAvailable()) return
        if (!isPidValid) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine probably crashed or detached.")
            context.toast("Engine crashed. Rebooting JamesDSP.", false)
            rebootEngine()
        }

        if (isSampleRateAbnormal) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine crashed.")
            context.toast("Abnormal sampling rate. Rebooting JamesDSP.", false)
            rebootEngine()
        }
    }

    private fun rebootEngine() {
        try {
            effect?.release()
            effect = createEffect()
        }
        catch (ex: IllegalStateException) {
            Timber.e("Failed to re-instantiate JamesDSP effect")
            Timber.e(ex.cause)
            effect = null
            return
        }
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        if(effect == null && !JamesDspControlClient.isAvailable()) {
            Timber.d("Rejecting update due to disposed engine")
            return
        }

        checkEngine()
        super.syncWithPreferences(forceUpdateNamespaces)
    }

    override fun preferenceUpdateOrder(targets: Array<String>) =
        orderNativePreferenceUpdates(targets, JamesDspControlClient.isAvailable())

    override fun close() {
        disposed = true
        context.unregisterLocalReceiver(broadcastReceiver)
        effect?.release()
        effect = null
        super.close()
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        // Limiter threshold/release still use the legacy path. Apply those
        // first so they cannot immediately cancel the queued native post-gain.
        val limiterApplied = effect.setParameterFloatArray(
            1500,
            floatArrayOf(threshold, release, postGain)
        ) == AudioEffect.SUCCESS
        val nativeQueued = JamesDspControlClient.setPostGainIfSupported(postGain) {
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
        if (filterType == 0 && bands.size == 30 && JamesDspControlClient.setFirEqIfSupported(
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
            effect.setParameterCharBuffer(12001, 10009, vdc)
            effect.setParameter(25001, currentCrc) // Commit hash
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
            effect.setParameterImpulseResponseBuffer(12000, 10004, impulseResponse, irChannels)
            effect.setParameter(25003, irCrc) // Commit hash
        }

        return effect.setParameter(1205, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        val prevCrc = this.graphicEqHash
        val currentCrc = bands.crc()

        Timber.i("GraphicEQ hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            effect.setParameterCharBuffer(12001, 10006, bands)
            effect.setParameter(25000, currentCrc) // Commit hash
        }

        return effect.setParameter(1210, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        val prevCrc = this.liveprogHash
        val currentCrc = script.crc()

        Timber.i("Liveprog hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            effect.setParameterCharBuffer(12001, 10010, script)
            effect.setParameter(25002, currentCrc) // Commit hash
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
        private val EFFECT_JAMESDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

        /** A mounted library is not enough: the audio factory must advertise it. */
        fun discoveredPluginState(): PluginState = try {
            AudioEffect.queryEffects()
                ?.firstOrNull { it.uuid == EFFECT_JAMESDSP }
                ?.run { if (name.contains("v3")) PluginState.Unsupported else PluginState.Available }
                ?: PluginState.Unavailable
        } catch (e: Exception) {
            Timber.w(e, "Unable to query the audio effect factory")
            PluginState.Unavailable
        }

        fun isPluginInstalled(): PluginState {
            // Global control is the primary root backend. Do not even query
            // AudioEffect or the legacy library unless the native probe failed.
            return resolvePluginAvailability(JamesDspControlClient.isAvailable()) {
                try {
                    // AudioEffect.queryEffects() can return null on vendor HALs
                    // that expose no descriptors yet. Treat it as empty so the
                    // filesystem fallback can run instead of crashing the UI.
                    val queriedEffects = AudioEffect.queryEffects() ?: emptyArray()
                    queriedEffects
                        .firstOrNull { it.uuid == EFFECT_JAMESDSP }
                        ?.run {
                            if(name.contains("v3")) PluginState.Unsupported else PluginState.Available
                        } ?: if (isPluginLibraryPresent()) PluginState.Available else PluginState.Unavailable
                } catch (e: Exception) {
                    Timber.e("isPluginInstalled: exception raised")
                    Timber.e(e)
                    MainApplication.instance.showAlert(
                        "Error while checking audio effect status",
                        "Unexpected error while checking whether JamesDSP's audio effect library is installed. \n\n" +
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
                "/vendor/lib64/soundfx/libjamesdsp.so",
                "/vendor/lib/soundfx/libjamesdsp.so",
                "/odm/lib64/soundfx/libjamesdsp.so",
                "/odm/lib/soundfx/libjamesdsp.so",
                "/system/lib64/soundfx/libjamesdsp.so",
                "/system/lib/soundfx/libjamesdsp.so",
            ).any { java.io.File(it).isFile }
        }
    }
}
