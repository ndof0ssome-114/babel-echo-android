package app.babelecho.android.recording

import kotlin.math.sqrt

data class AudioUtterance(val samples: ShortArray, val startSample: Long)

/** PCM sample clock excludes pauses, but includes silence. Only voice-bearing windows go to ASR. */
class UtteranceSegmenter(private val sampleRate: Int, maxSeconds: Int = 30, overlapSeconds: Double = 1.2,
    private val threshold: Double = 0.003, silenceSeconds: Double = 1.2) {
    private val maximum = sampleRate * maxSeconds
    private val overlap = (sampleRate * overlapSeconds).toInt().coerceIn(0, maximum / 2)
    private val silence = (sampleRate * silenceSeconds).toLong()
    private val preRoll = sampleRate * 3 / 10
    private val minimum = sampleRate / 4
    private val frame = (sampleRate / 50).coerceAtLeast(1)
    private val buffer = ShortArray(maximum + frame)
    private val idle = ShortArray(preRoll)
    private var idleSize = 0
    private var size = 0
    private var clock = 0L
    private var start = 0L
    private var lastVoice = 0L
    private var newVoice = false

    init { require(sampleRate > 0 && maxSeconds > 0 && silenceSeconds > 0 && threshold > 0) }

    fun append(samples: ShortArray): List<AudioUtterance> {
        val result = mutableListOf<AudioUtterance>()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(frame, samples.size - offset)
            var energy = 0.0
            for (i in offset until offset + count) { val value = samples[i] / 32768.0; energy += value * value }
            val voice = sqrt(energy / count) >= threshold
            val before = clock
            clock += count
            if (voice) {
                if (size == 0) {
                    idle.copyInto(buffer, 0, 0, idleSize); size = idleSize
                    start = before - idleSize; idleSize = 0
                }
                lastVoice = clock; newVoice = true
            }
            if (size > 0 || voice) {
                samples.copyInto(buffer, size, offset, offset + count); size += count
                if (!voice && clock - lastVoice >= silence) {
                    finish()?.let(result::add)
                } else if (size >= maximum) {
                    if (newVoice) result += AudioUtterance(buffer.copyOf(size), start)
                    buffer.copyInto(buffer, 0, size - overlap, size)
                    size = overlap; start = clock - overlap; newVoice = false
                }
            } else {
                val keep = minOf(idleSize, preRoll - count.coerceAtMost(preRoll))
                idle.copyInto(idle, 0, idleSize - keep, idleSize)
                val added = minOf(count, preRoll)
                samples.copyInto(idle, keep, offset + count - added, offset + count)
                idleSize = keep + added
            }
            offset += count
        }
        return result
    }

    /** Stop/pause flushes only new voice, never an already-finalized overlap or silent tail. */
    fun finish(): AudioUtterance? {
        val result = if (newVoice && size >= minimum) AudioUtterance(buffer.copyOf(size), start) else null
        size = 0; idleSize = 0; newVoice = false
        return result
    }
}
