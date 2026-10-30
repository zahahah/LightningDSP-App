package lightning.dsp.interop

import android.content.Context
import android.content.Intent
import lightning.dsp.interop.structure.EelVmVariable
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule

class LightningDspLocalEngine(context: Context, callbacks: LightningDspWrapper.LightningDspCallbacks? = null) : LightningDspBaseEngine(context, callbacks) {
    var handle: LightningDspHandle = LightningDspWrapper.alloc(callbacks ?: DummyCallbacks())

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            LightningDspWrapper.setSamplingRate(handle, value, false)
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    override fun close() {
        val oldHandle = handle
        handle = 0

        // Make sure ongoing async calls to native have enough time to finish
        Timer().schedule(100) {
            LightningDspWrapper.free(oldHandle)
            Timber.d("Handle $oldHandle has been freed")
        }
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            LightningDspWrapper.processInt16(handle, input, output, offset, length)
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            LightningDspWrapper.processInt32(handle, input, output, offset, length)
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            LightningDspWrapper.processFloat(handle, input, output, offset, length)
        }
    }

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return LightningDspWrapper.setLimiter(handle, threshold, release) and LightningDspWrapper.setPostGain(handle, postGain)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return LightningDspWrapper.setReverb(handle, enable, preset)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return LightningDspWrapper.setCrossfeed(handle, enable, mode, 0, 0)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return LightningDspWrapper.setCrossfeed(handle, enable, 99, fcut, feed)
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return LightningDspWrapper.setBassBoost(handle, enable, maxGain)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return LightningDspWrapper.setStereoEnhancement(handle, enable, level)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return LightningDspWrapper.setVacuumTube(handle, enable, level)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return LightningDspWrapper.setMultiEqualizer(handle, enable, filterType, interpolationMode, bands)
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return LightningDspWrapper.setCompander(handle, enable, timeConstant, granularity, tfTransforms, bands)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return LightningDspWrapper.setVdc(handle, enable, vdc)
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {
        return LightningDspWrapper.setConvolver(handle, enable, impulseResponse, irChannels, irFrames)
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        return LightningDspWrapper.setGraphicEq(handle, enable, bands)
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return LightningDspWrapper.setLiveprog(handle, enable, name, script)
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        return LightningDspWrapper.enumerateEelVariables(handle)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return LightningDspWrapper.manipulateEelVariable(handle, name, value)
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        LightningDspWrapper.freezeLiveprogExecution(handle, freeze)
    }
}