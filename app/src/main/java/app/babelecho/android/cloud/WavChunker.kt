package app.babelecho.android.cloud

import app.babelecho.android.recording.WavFileWriter
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

object WavChunker {
    fun split(source: File, cacheDirectory: File, chunkSeconds: Int, overlapSeconds: Int): List<File> {
        RandomAccessFile(source, "r").use { input ->
            require(input.length() >= 44) { "Invalid WAV file" }
            input.seek(22)
            val channels = readLeShort(input)
            val sampleRate = readLeInt(input)
            input.seek(34)
            val bits = readLeShort(input)
            require(channels == 1 && bits == 16) { "Only mono PCM16 WAV recordings are supported" }
            input.seek(40)
            val declaredDataBytes = readLeInt(input).toLong() and 0xffffffffL
            val dataBytes = min(declaredDataBytes, input.length() - 44)
            val bytesPerSecond = sampleRate * channels * bits / 8
            val chunkBytes = (chunkSeconds.toLong() * bytesPerSecond).coerceAtLeast(bytesPerSecond.toLong())
            val overlapBytes = (overlapSeconds.toLong() * bytesPerSecond).coerceAtMost(chunkBytes / 2)
            val stepBytes = (chunkBytes - overlapBytes).coerceAtLeast(bytesPerSecond.toLong())
            val result = mutableListOf<File>()
            var offset = 0L
            var index = 0
            while (offset < dataBytes) {
                val length = min(chunkBytes, dataBytes - offset).toInt()
                val pcm = ByteArray(length)
                input.seek(44 + offset)
                input.readFully(pcm)
                val samples = ShortArray(pcm.size / 2) { sampleIndex ->
                    val low = pcm[sampleIndex * 2].toInt() and 0xff
                    val high = pcm[sampleIndex * 2 + 1].toInt()
                    ((high shl 8) or low).toShort()
                }
                val output = File(cacheDirectory, "babel-asr-${System.nanoTime()}-${index++}.wav")
                WavFileWriter(output, sampleRate).use { it.write(samples) }
                result += output
                if (offset + length >= dataBytes) break
                offset += stepBytes
            }
            return result
        }
    }

    private fun readLeShort(file: RandomAccessFile): Int {
        val a = file.readUnsignedByte()
        val b = file.readUnsignedByte()
        return a or (b shl 8)
    }

    private fun readLeInt(file: RandomAccessFile): Int {
        val a = file.readUnsignedByte()
        val b = file.readUnsignedByte()
        val c = file.readUnsignedByte()
        val d = file.readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }
}
