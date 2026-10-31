package lightning.dsp.interop

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lightning.dsp.BuildConfig
import lightning.dsp.R
import lightning.dsp.interop.structure.EelVmVariable
import lightning.dsp.model.ProcessorMessage
import lightning.dsp.preference.FileLibraryPreference
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader

/** Legacy writes invalidate queued native generations. Commit native-capable
 * namespaces last so an idle-audio FIR update survives the same preference sync. */
internal fun orderNativePreferenceUpdates(targets: Array<String>, nativeReady: Boolean): Array<String> =
    if (!nativeReady) targets else targets.distinct().sortedBy {
        when (it) { Constants.PREF_OUTPUT -> 1; Constants.PREF_EQ -> 2; else -> 0 }
    }.toTypedArray()

abstract class LightningDspBaseEngine(val context: Context, val callbacks: LightningDspWrapper.LightningDspCallbacks? = null) : AutoCloseable {
    abstract var enabled: Boolean
    open var sampleRate: Float = 0.0f
        set(value) {
            field = value
            reportSampleRate(value)
        }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()
    private var appliedDesiredGeneration = -1L
    protected val cache = PreferenceCache(context)

    override fun close() {
        Timber.d("Closing engine")
        reportSampleRate(0f)
        syncScope.cancel()
    }

    open fun syncWithPreferences(forceUpdateNamespaces: Array<String>? = null) {
        val generation = DspDesiredState.current()
        syncScope.launch {
            syncWithPreferencesAsync(forceUpdateNamespaces, retryAttempt = 0, generation)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    protected open fun preferenceUpdateOrder(targets: Array<String>): Array<String> = targets

    private fun reportSampleRate(value: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, value)
        })
    }

    private suspend fun syncWithPreferencesAsync(
        forceUpdateNamespaces: Array<String>? = null,
        retryAttempt: Int,
        generation: Long,
    ) {
        Timber.d("Synchronizing with preferences... (forced: %s)", forceUpdateNamespaces?.joinToString(";") { it })

        var failedNamespaces: List<String> = emptyList()
        syncMutex.withLock {
          DspDesiredState.applyIfCurrent(generation) {
            if (appliedDesiredGeneration != generation) {
                cache.clear()
                appliedDesiredGeneration = generation
            }
            cache.select(Constants.PREF_OUTPUT)
            val extendedPostGain = cache.get(R.string.key_output_extra_postgain, PostGainRangePolicy.DEFAULT_EXTENDED)
            val outputPostGain = PostGainRangePolicy.clamp(cache.get(R.string.key_output_postgain, 0f),
                extendedPostGain && !BuildConfig.ROOTLESS)
            val limiterThreshold = cache.get(R.string.key_limiter_threshold, -0.1f)
            val limiterRelease = cache.get(R.string.key_limiter_release, 60f)

            cache.select(Constants.PREF_COMPANDER)
            val compEnabled = cache.get(R.string.key_compander_enable, false)
            val compTimeConst = cache.get(R.string.key_compander_timeconstant, 0.22f)
            val compGranularity = cache.get(R.string.key_compander_granularity, 2f).toInt()
            val compTfTransforms = cache.get(R.string.key_compander_tftransforms, "0").toInt()
            val compResponse = cache.get(R.string.key_compander_response, "95.0;200.0;400.0;800.0;1600.0;3400.0;7500.0;0;0;0;0;0;0;0")

            cache.select(Constants.PREF_BASS)
            val bassEnabled = cache.get(R.string.key_bass_enable, false)
            val bassMaxGain = cache.get(R.string.key_bass_max_gain, 5f)

            cache.select(Constants.PREF_EQ)
            val eqEnabled = cache.get(R.string.key_eq_enable, false)
            val eqFilterType = cache.get(R.string.key_eq_filter_type, "0").toInt()
            val eqInterpolationMode = cache.get(R.string.key_eq_interpolation, "0").toInt()
            val eqBands = cache.get(R.string.key_eq_bands, Constants.DEFAULT_EQ)

            cache.select(Constants.PREF_GEQ)
            val geqEnabled = cache.get(R.string.key_geq_enable, false)
            val geqBands = cache.get(R.string.key_geq_nodes, Constants.DEFAULT_GEQ_INTERNAL)

            cache.select(Constants.PREF_REVERB)
            val reverbEnabled = cache.get(R.string.key_reverb_enable, false)
            val reverbPreset = cache.get(R.string.key_reverb_preset, "0").toInt()

            cache.select(Constants.PREF_STEREOWIDE)
            val swEnabled = cache.get(R.string.key_stereowide_enable, false)
            val swMode = cache.get(R.string.key_stereowide_mode, 60f)

            cache.select(Constants.PREF_CROSSFEED)
            val crossfeedEnabled = cache.get(R.string.key_crossfeed_enable, false)
            val crossfeedMode = cache.get(R.string.key_crossfeed_mode, "0").toInt()

            cache.select(Constants.PREF_TUBE)
            val tubeEnabled = cache.get(R.string.key_tube_enable, false)
            val tubeDrive = cache.get(R.string.key_tube_drive, 2f)

            cache.select(Constants.PREF_DDC)
            val ddcEnabled = cache.get(R.string.key_ddc_enable, false)
            val ddcFile = cache.get(R.string.key_ddc_file, "")

            cache.select(Constants.PREF_LIVEPROG)
            val liveProgEnabled = cache.get(R.string.key_liveprog_enable, false)
            val liveprogFile = cache.get(R.string.key_liveprog_file, "")

            cache.select(Constants.PREF_CONVOLVER)
            val convolverEnabled = cache.get(R.string.key_convolver_enable, false)
            val convolverFile = cache.get(R.string.key_convolver_file, "")
            val convolverAdvImp = cache.get(R.string.key_convolver_adv_imp, Constants.DEFAULT_CONVOLVER_ADVIMP)
            val convolverMode = cache.get(R.string.key_convolver_mode, "0").toInt()

            val targets = (cache.changedNamespaces + (forceUpdateNamespaces ?: arrayOf())).distinct().toTypedArray()
            val successfulNamespaces = mutableListOf<String>()
            val failed = linkedSetOf<String>()
            preferenceUpdateOrder(targets).forEach {
                Timber.i("Committing new changes in namespace '$it'")

                val result = when (it) {
                    Constants.PREF_OUTPUT -> setOutputControl(limiterThreshold, limiterRelease, outputPostGain)
                    Constants.PREF_COMPANDER -> setCompander(compEnabled, compTimeConst, compGranularity, compTfTransforms, compResponse)
                    Constants.PREF_BASS -> setBassBoost(bassEnabled, bassMaxGain)
                    Constants.PREF_EQ -> setMultiEqualizer(eqEnabled, eqFilterType, eqInterpolationMode, eqBands)
                    Constants.PREF_GEQ -> setGraphicEq(geqEnabled, geqBands)
                    Constants.PREF_REVERB -> setReverb(reverbEnabled, reverbPreset)
                    Constants.PREF_STEREOWIDE -> setStereoEnhancement(swEnabled, swMode)
                    Constants.PREF_CROSSFEED -> setCrossfeed(crossfeedEnabled, crossfeedMode)
                    Constants.PREF_TUBE -> setVacuumTube(tubeEnabled, tubeDrive)
                    Constants.PREF_DDC -> setVdc(ddcEnabled, ddcFile)
                    Constants.PREF_LIVEPROG -> setLiveprog(liveProgEnabled, liveprogFile)
                    Constants.PREF_CONVOLVER -> setConvolver(convolverEnabled, convolverFile, convolverMode, convolverAdvImp)
                    else -> true
                }

                if(!result) {
                    Timber.e("Failed to apply $it")
                    failed.add(it)
                    cache.markNamespaceAsChanged(it)
                } else {
                    successfulNamespaces.add(it)
                }
            }

            cache.markChangesAsCommitted(successfulNamespaces)
            failedNamespaces = failed.toList()
            Timber.i("Preferences synchronized (desired generation=%s)", generation)
          }
        }

        // Route/profile callbacks can arrive while AudioFlinger is switching
        // outputs. Do not forget failed writes: bounded retries re-read the
        // current profile and reapply only namespaces that did not succeed.
        if (failedNamespaces.isNotEmpty() && retryAttempt < MAX_PREFERENCE_SYNC_RETRIES) {
            val retryDelay = PREFERENCE_SYNC_RETRY_DELAYS_MS[retryAttempt]
            Timber.w("Retrying failed DSP namespaces after route/profile sync: %s", failedNamespaces)
            syncScope.launch {
                delay(retryDelay)
                syncWithPreferencesAsync(failedNamespaces.toTypedArray(), retryAttempt + 1, generation)
            }
        }
    }

    private companion object {
        const val MAX_PREFERENCE_SYNC_RETRIES = 3
        val PREFERENCE_SYNC_RETRY_DELAYS_MS = longArrayOf(250, 500, 1_000)
    }

    fun setMultiEqualizer(enable: Boolean, filterType: Int, interpolationMode: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(30)
        val array = bands.split(";")
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setFirEqualizer: malformed EQ string")
                return false
            }
            doubleArray[i] = number
        }

        return setMultiEqualizerInternal(enable, filterType, interpolationMode, doubleArray)
    }

    fun setCompander(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(14)
        val array = bands.split(";")
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setCompander: malformed string")
                return false
            }
            doubleArray[i] = number
        }

        return setCompanderInternal(enable, timeConstant, granularity, tfTransforms, doubleArray)
    }

    fun setVdc(enable: Boolean, vdcPath: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, vdcPath)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setVdc: file does not exist")
            setVdcInternal(false, "")
            return true /* non-critical */
        }

        return safeFileReader(fullPath)?.use {
            setVdcInternal(enable, it.readText())
        } ?: false
    }

    fun setConvolver(enable: Boolean, impulseResponsePath: String, optimizationMode: Int, waveEditStr: String): Boolean
    {
        val path = FileLibraryPreference.createFullPathCompat(context, impulseResponsePath)

        // Handle disabled state before everything else
        if(!enable || !File(path).exists() || File(path).isDirectory) {
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            return true
        }

        val advConv = waveEditStr.split(";")
        val advSetting = IntArray(6)
        advSetting.fill(0)
        advSetting[0] = -80
        advSetting[1] = -100
        try
        {
            if (advConv.size == 6)
            {
                for (i in advConv.indices) advSetting[i] = Integer.valueOf(advConv[i])
            }
            else {
                Timber.w("setConvolver: AdvImp setting has the wrong size (${advConv.size})")
                callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
            }
        }
        catch(ex: NumberFormatException) {
            Timber.e("setConvolver: NumberFormatException while parsing AdvImp setting. Using defaults.")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        val info = IntArray(4)
        val imp = LdspImpResToolbox.ReadImpulseResponseToFloat(
            path,
            sampleRate.toInt(),
            info,
            optimizationMode,
            advSetting
        )

        if(imp == null) {
            Timber.e("setConvolver: Failed to read IR")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.Corrupted)
            return false
        }

        // check frame count
        if(info[1] == 0) {
            Timber.e("setConvolver: IR has no frames")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.NoFrames)
            return false
        }

        // check if advSetting was invalid
        if(info[3] == 0) {
            Timber.w("setConvolver: advSetting was invalid")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        return setConvolverInternal(true, imp, info[0], info[1], info[2])
    }

    fun setGraphicEq(enable: Boolean, bands: String): Boolean
    {
        // Sanity check
        if(!bands.contains("GraphicEQ:", true)) {
            Timber.e("setGraphicEq: malformed string")
            setGraphicEqInternal(false, "")
            return false
        }

        return setGraphicEqInternal(enable, bands)
    }

    fun setLiveprog(enable: Boolean, path: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, path)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setLiveprog: file does not exist")
            return setLiveprogInternal(false, "", "")
        }

        return safeFileReader(fullPath)?.use {
            val name = File(fullPath).name
            setLiveprogInternal(enable, name, it.readText())
        } ?: false
    }

    private fun safeFileReader(path: String) =
        try { FileReader(path) }
        catch (ex: FileNotFoundException) {
            /* Exception may occur when old presets created with version <1.4.3 are swapped
               between root, rootless, debug, or release builds due to path name differences. */
            Timber.w(ex)
            null
        }

    // Effect config
    abstract fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean
    abstract fun setReverb(enable: Boolean, preset: Int): Boolean
    abstract fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    abstract fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    abstract fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    abstract fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    abstract fun setVacuumTube(enable: Boolean, level: Float): Boolean

    protected abstract fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean
    protected abstract fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray): Boolean
    protected abstract fun setVdcInternal(enable: Boolean, vdc: String): Boolean
    protected abstract fun setConvolverInternal(enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int, irCrc: Int): Boolean
    protected abstract fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean
    protected abstract fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean

    // Feature support
    abstract fun supportsEelVmAccess(): Boolean
    abstract fun supportsCustomCrossfeed(): Boolean

    // EEL VM utilities
    abstract fun enumerateEelVariables(): ArrayList<EelVmVariable>
    abstract fun manipulateEelVariable(name: String, value: Float): Boolean
    abstract fun freezeLiveprogExecution(freeze: Boolean)

    protected inner class DummyCallbacks : LightningDspWrapper.LightningDspCallbacks
    {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }
}
