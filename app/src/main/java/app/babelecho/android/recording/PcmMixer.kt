package app.babelecho.android.recording

import kotlin.math.abs

/** One captured block, positioned on its own source's sample timeline. */
class PcmFrame(val source: Int, val position: Long, val samples: ShortArray)

/**
 * Mixes independently captured mono PCM16 sources onto one timeline.
 *
 * Every source starts together at the same rate, so sample counts are the shared clock.
 * Frames lost before reaching the mixer leave a position gap that becomes silence instead
 * of pulling the rest of that source earlier. When one source stops delivering for more
 * than [maxLagSamples], it is padded with silence so the other is not held back.
 */
class PcmMixer(sourceCount: Int, private val maxLagSamples: Int) {
    private val pending = Array(sourceCount) { PcmFifo() }
    private val nextPosition = LongArray(sourceCount)

    /** Silence inserted for frames that never arrived, in samples per source. */
    var gapSamples = 0L
        private set

    fun add(frame: PcmFrame) {
        val fifo = pending[frame.source]
        val gap = frame.position - nextPosition[frame.source]
        var from = 0
        if (gap > 0) {
            fifo.addSilence(gap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            gapSamples += gap
        } else if (gap < 0) {
            from = (-gap).coerceAtMost(frame.samples.size.toLong()).toInt()
        }
        fifo.add(frame.samples, from)
        nextPosition[frame.source] = maxOf(nextPosition[frame.source], frame.position + frame.samples.size)
    }

    /** Returns the samples every source has reached; [flush] pads all sources to the longest. */
    fun mix(flush: Boolean = false): ShortArray {
        val longest = pending.maxOf { it.size }
        val shortest = pending.minOf { it.size }
        val ready = if (flush || longest - shortest > maxLagSamples) longest else shortest
        if (ready == 0) return EMPTY
        pending.forEach { if (it.size < ready) it.addSilence(ready - it.size) }
        if (pending.size == 1) return pending[0].take(ready)
        val sums = IntArray(ready)
        pending.forEach { it.takeInto(sums) }
        return ShortArray(ready) { limit(sums[it]) }
    }

    /** Drops buffered audio (used while paused) and realigns the sources at the next frames. */
    fun discard() = pending.forEach { it.clear() }

    companion object {
        private val EMPTY = ShortArray(0)
        private const val KNEE = 24_576
        private const val HEADROOM = Short.MAX_VALUE - KNEE

        /** Soft limiter: unchanged below the knee, approaches but never reaches full scale above it. */
        fun limit(sum: Int): Short {
            val magnitude = abs(sum)
            if (magnitude <= KNEE) return sum.toShort()
            val excess = (magnitude - KNEE).toLong()
            val limited = KNEE + (HEADROOM * excess / (excess + HEADROOM)).toInt()
            return (if (sum < 0) -limited else limited).toShort()
        }
    }
}

private class PcmFifo {
    private var data = ShortArray(4_800)
    var size = 0
        private set

    fun add(samples: ShortArray, from: Int) {
        val count = samples.size - from
        ensure(size + count)
        samples.copyInto(data, size, from, samples.size)
        size += count
    }

    fun addSilence(count: Int) {
        ensure(size + count)
        data.fill(0, size, size + count)
        size += count
    }

    fun take(count: Int): ShortArray = data.copyOfRange(0, count).also { drop(count) }

    fun takeInto(sums: IntArray) {
        for (i in sums.indices) sums[i] += data[i]
        drop(sums.size)
    }

    fun clear() { size = 0 }

    private fun drop(count: Int) {
        data.copyInto(data, 0, count, size)
        size -= count
    }

    private fun ensure(required: Int) {
        if (required > data.size) data = data.copyOf(maxOf(required, data.size * 2))
    }
}
