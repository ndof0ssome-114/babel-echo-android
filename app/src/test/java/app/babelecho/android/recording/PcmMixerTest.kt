package app.babelecho.android.recording

import org.junit.Assert.*
import org.junit.Test

class PcmMixerTest {
    private fun frame(source: Int, position: Long, vararg samples: Int) =
        PcmFrame(source, position, ShortArray(samples.size) { samples[it].toShort() })

    private fun ShortArray.ints() = map { it.toInt() }

    @Test fun singleSourcePassesThroughUnchanged() {
        val mixer = PcmMixer(1, maxLagSamples = 100)
        mixer.add(frame(0, 0, 1, 2, 3))
        mixer.add(frame(0, 3, 32_767, -32_768))
        assertEquals(listOf(1, 2, 3, 32_767, -32_768), mixer.mix().ints())
        assertEquals(0, mixer.mix().size)
    }

    @Test fun sourcesAreMixedBySamplePositionNotArrivalOrder() {
        val mixer = PcmMixer(2, maxLagSamples = 100)
        mixer.add(frame(0, 0, 10, 20))
        mixer.add(frame(0, 2, 30, 40))
        assertEquals("waits for the other source", 0, mixer.mix().size)
        mixer.add(frame(1, 0, 1, 2, 3))
        assertEquals(listOf(11, 22, 33), mixer.mix().ints())
        mixer.add(frame(1, 3, 4))
        assertEquals(listOf(44), mixer.mix().ints())
    }

    @Test fun droppedFrameBecomesSilenceAndKeepsLaterAudioAligned() {
        val mixer = PcmMixer(2, maxLagSamples = 100)
        mixer.add(frame(0, 0, 1, 1))
        mixer.add(frame(0, 4, 1, 1)) // samples 2..3 were dropped
        mixer.add(frame(1, 0, 10, 10, 10, 10, 10, 10))
        assertEquals(listOf(11, 11, 10, 10, 11, 11), mixer.mix().ints())
        assertEquals(2L, mixer.gapSamples)
    }

    @Test fun stalledSourceIsPaddedOnlyAfterTheLagLimit() {
        val mixer = PcmMixer(2, maxLagSamples = 4)
        mixer.add(frame(0, 0, 5, 5, 5, 5))
        assertEquals(0, mixer.mix().size)
        mixer.add(frame(0, 4, 5))
        assertEquals(listOf(5, 5, 5, 5, 5), mixer.mix().ints())
        // The stalled source resumes from where it left off on the shared timeline.
        mixer.add(frame(1, 0, 1))
        mixer.add(frame(0, 5, 5))
        assertEquals(listOf(6), mixer.mix().ints())
    }

    @Test fun flushWritesEverythingStillBuffered() {
        val mixer = PcmMixer(2, maxLagSamples = 100)
        mixer.add(frame(0, 0, 7, 7, 7))
        mixer.add(frame(1, 0, 1))
        assertEquals(listOf(8), mixer.mix().ints())
        assertEquals(listOf(7, 7), mixer.mix(flush = true).ints())
    }

    @Test fun discardRealignsSourcesAfterPause() {
        val mixer = PcmMixer(2, maxLagSamples = 100)
        mixer.add(frame(0, 0, 1, 1, 1))
        mixer.discard()
        mixer.add(frame(0, 3, 2))
        mixer.add(frame(1, 0, 20))
        assertEquals(listOf(22), mixer.mix().ints())
        assertEquals(0L, mixer.gapSamples)
    }

    @Test fun jitteredArrivalKeepsEverySampleInPlace() {
        val random = java.util.Random(7)
        val total = 48_000
        val mic = IntArray(total) { random.nextInt(2_000) - 1_000 }
        val playback = IntArray(total) { random.nextInt(2_000) - 1_000 }
        val mixer = PcmMixer(2, maxLagSamples = total)
        val output = ArrayList<Int>()
        var micAt = 0
        var playbackAt = 0
        while (micAt < total || playbackAt < total) {
            // Uneven bursts of 0–3 frames per source, each with a variable size.
            repeat(random.nextInt(4)) {
                if (micAt < total) {
                    val n = minOf(total - micAt, 400 + random.nextInt(1_120))
                    mixer.add(PcmFrame(0, micAt.toLong(), ShortArray(n) { mic[micAt + it].toShort() })); micAt += n
                }
            }
            repeat(random.nextInt(4)) {
                if (playbackAt < total) {
                    val n = minOf(total - playbackAt, 400 + random.nextInt(1_120))
                    mixer.add(PcmFrame(1, playbackAt.toLong(), ShortArray(n) { playback[playbackAt + it].toShort() })); playbackAt += n
                }
            }
            output += mixer.mix().ints()
        }
        output += mixer.mix(flush = true).ints()
        assertEquals(total, output.size)
        assertEquals(List(total) { mic[it] + playback[it] }, output)
    }

    @Test fun limiterLeavesNormalLevelsAndNeverSaturates() {
        assertEquals(20_000, PcmMixer.limit(20_000).toInt())
        assertEquals(-24_576, PcmMixer.limit(-24_576).toInt())
        val loud = PcmMixer.limit(65_534).toInt()
        assertTrue(loud in 24_577 until 32_767)
        assertEquals(-loud, PcmMixer.limit(-65_534).toInt())
        var previous = 0
        for (sum in 0..65_536 step 97) {
            val value = PcmMixer.limit(sum).toInt()
            assertTrue("monotonic at $sum", value >= previous)
            previous = value
        }
        val mixer = PcmMixer(2, maxLagSamples = 100)
        mixer.add(frame(0, 0, 32_767, -32_768))
        mixer.add(frame(1, 0, 32_767, -32_768))
        mixer.mix().forEach { assertTrue(it.toInt() in -32_766..32_766) }
    }
}
