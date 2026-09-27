package app.babelecho.android.recording

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) : Closeable {
    private val output = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(44))
    }
    private var dataBytes = 0L

    fun write(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        output.write(bytes)
        dataBytes += bytes.size
    }

    override fun close() {
        output.seek(0)
        output.writeBytes("RIFF")
        writeLeInt((36L + dataBytes).coerceAtMost(0xffffffffL).toInt())
        output.writeBytes("WAVEfmt ")
        writeLeInt(16)
        writeLeShort(1)
        writeLeShort(channels)
        writeLeInt(sampleRate)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        writeLeInt(byteRate)
        writeLeShort(channels * bitsPerSample / 8)
        writeLeShort(bitsPerSample)
        output.writeBytes("data")
        writeLeInt(dataBytes.coerceAtMost(0xffffffffL).toInt())
        output.close()
    }

    private fun writeLeInt(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
        output.write(value ushr 16 and 0xff)
        output.write(value ushr 24 and 0xff)
    }

    private fun writeLeShort(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
    }
}
