package app.babelecho.android.recording

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

object MediaImporter {
    /** Streams the selected media's first audio track into an app-private 16 kHz mono WAV. */
    fun decode(context: Context, uri: Uri, output: File): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var written = 0L
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("所选文件没有可导入的音轨 / No audio track in this file")
            extractor.selectTrack(track)
            var format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            var resampler = PcmResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            WavFileWriter(output, 16_000).use { writer ->
                fun writePcm(buffer: ByteBuffer, activeFormat: MediaFormat) {
                    val channels = activeFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val encoding = if (activeFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) activeFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(channels > 0 && encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
                        "Unsupported decoded audio format"
                    }
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val mono = DoubleArray(buffer.remaining() / bytes / channels) {
                        var sum = 0.0
                        repeat(channels) { sum += if (bytes == 4) buffer.float.toDouble() * 32767 else buffer.short.toDouble() }
                        sum / channels
                    }
                    val samples = resampler.convert(mono)
                    written += samples.size
                    require(written * 2 < 0xffff0000L) { "Recording exceeds WAV size limit" }
                    writer.write(samples)
                }

                if (mime == "audio/raw") {
                    val buffer = ByteBuffer.allocate(max(1_048_576, if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0))
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        buffer.position(0); buffer.limit(size)
                        writePcm(buffer, format)
                        extractor.advance()
                    }
                } else {
                    val codec = MediaCodec.createDecoderByType(mime)
                    decoder = codec
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var lastProgress = System.nanoTime()
                    while (!outputDone) {
                        if (!inputDone) {
                            val index = codec.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                                lastProgress = System.nanoTime()
                            }
                        }
                        when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                format = codec.outputFormat
                                resampler = PcmResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                                lastProgress = System.nanoTime()
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                        writePcm(buffer, format)
                                    }
                                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                } finally { codec.releaseOutputBuffer(index, false) }
                                lastProgress = System.nanoTime()
                            }
                        }
                        check(System.nanoTime() - lastProgress < 30_000_000_000L) { "Audio decoder timed out" }
                    }
                }
            }
            require(written > 0) { "所选音轨为空 / The audio track is empty" }
            return written * 1_000 / 16_000
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
