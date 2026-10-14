package app.babelecho.android.recording

/** Continuous mono linear resampling; phase is preserved across decoder buffers. */
class PcmResampler(private val inputRate: Int, private val outputRate: Int = 16_000) {
    private var inputIndex = 0L
    private var outputIndex = 0L
    private var previous = 0.0

    init { require(inputRate > 0 && outputRate > 0) }

    fun convert(mono: DoubleArray): ShortArray {
        val output = ArrayList<Short>()
        mono.forEach { current ->
            val position = inputIndex++
            if (position == 0L) previous = current
            while (outputIndex * inputRate.toDouble() / outputRate <= position) {
                val fraction = (outputIndex * inputRate.toDouble() / outputRate - (position - 1)).coerceIn(0.0, 1.0)
                output += (previous + (current - previous) * fraction).toInt().coerceIn(-32768, 32767).toShort()
                outputIndex++
            }
            previous = current
        }
        return output.toShortArray()
    }
}
