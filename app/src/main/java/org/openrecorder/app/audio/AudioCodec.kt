package org.openrecorder.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.openrecorder.app.core.Wav
import org.openrecorder.app.core.WavInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Platform codecs only: no bundled decoders, so supported input formats follow the device. */
object AudioCodec {
    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes the first audio track of [source] (M4A/AAC, MP3, OGG/Opus, FLAC, AMR, WAV variants…)
     * into a 16-bit PCM WAV. More than two channels are downmixed to stereo.
     */
    fun decodeToWav(source: File, target: File, progress: (Float) -> Unit = {}): WavInfo {
        require(source.canonicalPath != target.canonicalPath)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("音声トラックがありません")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var outChannels = inChannels.coerceAtMost(2)
            val info = MediaCodec.BufferInfo()
            var samples = FloatArray(inChannels)
            var written = 0L
            var inputDone = false; var outputDone = false
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0); out.write(ByteArray(44))
                val pcm = ByteBuffer.allocate(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                while (!outputDone) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                            if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outChannels = inChannels.coerceAtMost(2)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytesPerSample = if (float) 4 else 2
                        val frames = info.size / (bytesPerSample * inChannels)
                        if (samples.size != inChannels) samples = FloatArray(inChannels)
                        for (frame in 0 until frames) {
                            for (c in 0 until inChannels) samples[c] = if (float) buffer.float else buffer.short / 32768f
                            for (c in 0 until outChannels) {
                                // Downmix: left takes even channels, right takes odd channels.
                                val v = if (inChannels <= 2) samples[c] else {
                                    var sum = 0f; var n = 0
                                    for (i in c until inChannels step 2) { sum += samples[i]; n++ }
                                    sum / n
                                }
                                pcm.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                if (!pcm.hasRemaining()) { out.write(pcm.array(), 0, pcm.position()); written += pcm.position(); pcm.clear() }
                            }
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (durationUs > 0) progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                out.write(pcm.array(), 0, pcm.position()); written += pcm.position()
                require(written > 0) { "音声を読み取れませんでした" }
                require(written <= Wav.MAX_BYTES) { "音声が長すぎます" }
                out.seek(0); out.write(Wav.header(written, rate, outChannels)); out.fd.sync()
            }
            return Wav.inspect(target)
        } catch (e: Throwable) { target.delete(); throw e }
        finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Encodes a 16-bit WAV to AAC-LC in an MP4 (.m4a) container. */
    fun encodeAac(source: File, target: File, progress: (Float) -> Unit = {}): File {
        require(source.canonicalPath != target.canonicalPath)
        val wav = Wav.inspect(source)
        val bitRate = when {
            wav.sampleRate <= 16000 -> 32_000 * wav.channels
            wav.channels == 1 -> 96_000
            else -> 160_000
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var started = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            val info = MediaCodec.BufferInfo()
            RandomAccessFile(source, "r").use { input ->
                input.seek(wav.offset)
                var read = 0L; var inputDone = false; var outputDone = false
                while (!outputDone) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (!inputDone) {
                        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = encoder.getInputBuffer(index)!!
                            val count = minOf(buffer.capacity().toLong() / wav.frameBytes * wav.frameBytes, wav.bytes - read).toInt()
                            val presentationUs = read / wav.frameBytes * 1_000_000 / wav.sampleRate
                            if (count <= 0) { encoder.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else {
                                val bytes = ByteArray(count); input.readFully(bytes); read += count
                                buffer.clear(); buffer.put(bytes)
                                encoder.queueInputBuffer(index, 0, count, presentationUs, 0)
                                progress(read.toFloat() / wav.bytes)
                            }
                        }
                    }
                    val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true
                    } else if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) { buffer.position(info.offset); buffer.limit(info.offset + info.size); muxer.writeSampleData(track, buffer, info) }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            check(started) { "AACエンコーダーが出力を開始しませんでした" }
            muxer.stop()
            return target
        } catch (e: Throwable) { target.delete(); throw e }
        finally {
            runCatching { encoder.stop() }; runCatching { encoder.release() }
            runCatching { muxer?.release() }
        }
    }
}
