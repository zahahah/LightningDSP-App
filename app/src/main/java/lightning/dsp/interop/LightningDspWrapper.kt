package lightning.dsp.interop

import lightning.dsp.interop.structure.EelVmVariable
import lightning.dsp.model.ProcessorMessage

typealias LightningDspHandle = Long

object LightningDspWrapper {
    // Memory management
    external fun alloc(callbacks: LightningDspCallbacks): LightningDspHandle
    external fun free(self: LightningDspHandle)
    external fun isHandleValid(self: LightningDspHandle): Boolean

    // Benchmarking
    external fun getBenchmarkSize(): Int
    external fun runBenchmark(c0: DoubleArray, c1: DoubleArray)
    external fun loadBenchmark(c0: DoubleArray, c1: DoubleArray)

    // Processing (interleaved)
    external fun processInt16(self: LightningDspHandle, input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    external fun processInt8U24(self: LightningDspHandle, input: IntArray): IntArray
    external fun processInt24Packed(self: LightningDspHandle, input: BooleanArray): BooleanArray
    external fun processInt32(self: LightningDspHandle, input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    external fun processFloat(self: LightningDspHandle, input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)

    // Engine config
    external fun setSamplingRate(self: LightningDspHandle, sampleRate: Float, forceRefresh: Boolean)

    // Effect config
    external fun setLimiter(self: LightningDspHandle, threshold: Float, release: Float): Boolean
    external fun setPostGain(self: LightningDspHandle, postGain: Float): Boolean
    external fun setMultiEqualizer(self: LightningDspHandle, enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean
    external fun setVdc(self: LightningDspHandle, enable: Boolean, vdcContents: String): Boolean
    external fun setCompander(self: LightningDspHandle, enable: Boolean, timeConstant: Float, granularity: Int, tfResolution: Int, bands: DoubleArray): Boolean
    external fun setReverb(self: LightningDspHandle, enable: Boolean, preset: Int): Boolean
    external fun setConvolver(self: LightningDspHandle, enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int): Boolean
    external fun setGraphicEq(self: LightningDspHandle, enable: Boolean, graphicEq: String): Boolean
    external fun setCrossfeed(self: LightningDspHandle, enable: Boolean, mode: Int, customFcut: Int, customFeed: Int): Boolean
    external fun setBassBoost(self: LightningDspHandle, enable: Boolean, maxGain: Float): Boolean
    external fun setStereoEnhancement(self: LightningDspHandle, enable: Boolean, level: Float): Boolean
    external fun setVacuumTube(self: LightningDspHandle, enable: Boolean, level: Float): Boolean
    external fun setLiveprog(self: LightningDspHandle, enable: Boolean, id: String, liveprogContent: String): Boolean

    // EEL VM utilities
    external fun enumerateEelVariables(self: LightningDspHandle): ArrayList<EelVmVariable>
    external fun manipulateEelVariable(self: LightningDspHandle, name: String, value: Float): Boolean
    external fun freezeLiveprogExecution(self: LightningDspHandle, freeze: Boolean)
    external fun eelErrorCodeToString(errorCode: Int): String

    // Callbacks
    interface LightningDspCallbacks
    {
        fun onLiveprogOutput(message: String)
        fun onLiveprogExec(id: String)
        fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?)
        fun onVdcParseError()
        fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode)
    }

    init
    {
        System.loadLibrary("lightningdsp-wrapper")
    }
}