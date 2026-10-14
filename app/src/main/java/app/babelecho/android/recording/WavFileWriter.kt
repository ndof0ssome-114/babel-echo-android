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
    private var closed = false

    fun write(samples: ShortArray) {
        check(!closed) { "WAV has already closed" }
        require(dataBytes + samples.size * 2L < 0xffff0000L) { "WAV recording exceeds the format size limit" }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        output.write(bytes)
        dataBytes += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        try { writeHeader(output, sampleRate, channels, bitsPerSample, dataBytes) } finally { output.close() }
    }

    companion object {
        fun recover(file: File, sampleRate: Int): Long = RandomAccessFile(file, "rw").use { output ->
            require(output.length() > 44) { "Recording contains no audio" }
            val length = (output.length() - 44) / 2 * 2
            output.setLength(length + 44)
            writeHeader(output, sampleRate, 1, 16, length)
            length * 1_000 / (sampleRate * 2L)
        }

        private fun writeHeader(output: RandomAccessFile, sampleRate: Int, channels: Int, bitsPerSample: Int, dataBytes: Long) {
        fun writeLeInt(value: Int) { repeat(4) { output.write(value ushr (it * 8) and 0xff) } }
        fun writeLeShort(value: Int) { repeat(2) { output.write(value ushr (it * 8) and 0xff) } }
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
        }
    }
}
