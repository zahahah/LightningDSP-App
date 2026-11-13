package lightning.dsp.interop.appmixer

/** Assemble one constant DSP quantum from arbitrary non-blocking capture fragments.
 * A short/empty capture never reconfigures FIR processing or replays stale samples. */
internal class AppMixerFixedDspCapture(
    private val samples: Int,
    private val process: (ShortArray, ShortArray, Int) -> Unit,
) {
    init { require(samples in 2..65536 && samples % 2 == 0) }
    private val input = ShortArray(samples)
    private var pending = 0

    fun read(output: ShortArray, capture: (ShortArray, Int, Int) -> Int): Int {
        require(output.size >= samples)
        val available = samples - pending
        val count = capture(input, pending, available)
        if (count < 0) return count
        check(count <= available) { "Capture exceeded the requested quantum" }
        pending += count
        if (pending != samples) return 0
        process(input, output, samples)
        pending = 0
        return samples
    }
}
