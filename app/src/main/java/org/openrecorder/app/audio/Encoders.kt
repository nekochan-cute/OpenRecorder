package org.openrecorder.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import org.openrecorder.app.core.Wav
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

/** Storage format of a recording, chosen by file extension. */
enum class RecordFormat(val extension: String, val mime: String, val label: String) {
    Wav("wav", "audio/wav", "WAV（無圧縮）"),
    Aac("m4a", "audio/mp4", "AAC（M4A）"),
    Mp3("mp3", "audio/mpeg", "MP3");
    companion object {
        fun of(name: String) = entries.firstOrNull { it.name.equals(name, true) } ?: Wav
        fun ofFile(fileName: String) = when (fileName.substringAfterLast('.').lowercase()) { "m4a", "aac" -> Aac; "mp3" -> Mp3; else -> Wav }
        fun bitRate(rate: Int, channels: Int) = if (rate <= 16000) 32_000 * channels else if (channels == 1) 96_000 else 160_000
    }
}

/** Receives interleaved 16-bit PCM while recording. */
interface PcmSink : Closeable {
    fun write(pcm: ShortArray, count: Int)
}

/** WAV with the header rewritten every second, so a crash leaves a playable file. */
class WavSink(file: File, private val rate: Int, private val channels: Int) : PcmSink {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(Wav.header(0, rate, channels)) }
    private var bytes = 0L; private var sinceSync = 0L
    override fun write(pcm: ShortArray, count: Int) {
        check(bytes + count * 2 <= Wav.MAX_BYTES) { "WAVの上限に達しました。録音を分割してください" }
        val b = ByteArray(count * 2)
        for (i in 0 until count) { val v = pcm[i].toInt(); b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte() }
        out.write(b); bytes += b.size; sinceSync += b.size
        if (sinceSync >= rate * channels * 2L) { checkpoint(); sinceSync = 0 }
    }
    private fun checkpoint() { val pos = out.filePointer; out.seek(0); out.write(Wav.header(bytes, rate, channels)); out.seek(pos); out.fd.sync() }
    override fun close() { checkpoint(); out.close() }
}

/**
 * AAC-LC written as an ADTS stream while recording (every frame is self-contained, so a crash
 * loses at most the last frames). [AudioCodec.remuxAdts] turns it into M4A afterwards.
 */
class AdtsSink(file: File, private val rate: Int, private val channels: Int, bitRate: Int = RecordFormat.bitRate(rate, channels)) : PcmSink {
    private val out = FileOutputStream(file)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private var frames = 0L
    private val rateIndex = ADTS_RATES.indexOf(rate).also { require(it >= 0) { "AACで使えないサンプリングレートです" } }

    override fun write(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(false); continue }
            val buffer = codec.getInputBuffer(index)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val n = minOf(count - offset, buffer.capacity() / 2 / channels * channels)
            buffer.clear(); buffer.asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(index, 0, n * 2, frames * 1_000_000 / rate, 0)
            frames += n / channels; offset += n
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (endOfStream && ++waits < 300) continue else return }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)!!
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val frame = ByteArray(info.size); buffer.position(info.offset); buffer.get(frame)
                out.write(adtsHeader(frame.size)); out.write(frame)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun adtsHeader(payload: Int): ByteArray {
        val length = payload + 7
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(), ((length shr 3) and 0xFF).toByte(), (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
    }

    override fun close() {
        try {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, frames * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally { runCatching { codec.stop() }; codec.release(); out.fd.sync(); out.close() }
    }

    companion object {
        val ADTS_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

/** MP3 through jump3r, a Java port of LAME. Each frame is self-contained, so the file stays playable after a crash. */
class Mp3Sink(file: File, private val rate: Int, private val channels: Int, bitRateKbps: Int = RecordFormat.bitRate(rate, channels) / 1000) : PcmSink {
    private val out = FileOutputStream(file)
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private val encoded = ByteArray(1 shl 17)

    init {
        val ga = GainAnalysis(); val bs = BitStream(); val p = Presets(); val qupvt = QuantizePVT(); val qu = Quantize()
        val vbr = VBRTag(); val ver = Version(); val id3 = ID3Tag(); val rv = Reservoir(); val tak = Takehiro(); val parse = Parse()
        val mpg = MPGLib(); val intf = Interface(); val common = Common()
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr); id3.setModules(bs, ver); p.setModules(lame); qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy); rv.setModules(bs); tak.setModules(qupvt); vbr.setModules(lame, bs, ver)
        GetAudio().setModules(parse, mpg); parse.setModules(ver, id3, p); mpg.setModules(intf, common); intf.setModules(vbr, common)
        flags = lame.lame_init()
        flags.num_channels = channels; flags.in_samplerate = rate; flags.brate = bitRateKbps; flags.quality = 5
        id3.id3tag_init(flags); flags.write_id3tag_automatic = false
        check(lame.lame_init_params(flags) >= 0) { "MP3エンコーダーを初期化できません" }
    }

    override fun write(pcm: ShortArray, count: Int) {
        val frames = count / channels
        val left = IntArray(frames) { pcm[it * channels].toInt() shl 16 }
        val right = if (channels == 2) IntArray(frames) { pcm[it * 2 + 1].toInt() shl 16 } else left
        val n = lame.lame_encode_buffer_int(flags, left, right, frames, encoded, 0, encoded.size)
        check(n >= 0) { "MP3に変換できませんでした" }
        out.write(encoded, 0, n)
    }

    override fun close() {
        try { out.write(encoded, 0, lame.lame_encode_flush(flags, encoded, 0, encoded.size).coerceAtLeast(0)) }
        finally { lame.lame_close(flags); out.fd.sync(); out.close() }
    }
}

/** Runs a slower encoder on its own thread so microphone reads are never delayed. */
class ThreadedSink(private val inner: PcmSink) : PcmSink {
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val failure = AtomicReference<Throwable?>()
    private val thread = Thread({
        try { while (true) { val pcm = queue.take(); if (pcm.isEmpty()) break; inner.write(pcm, pcm.size) } }
        catch (e: Throwable) { failure.set(e) }
    }, "encoder").apply { start() }

    override fun write(pcm: ShortArray, count: Int) {
        failure.get()?.let { throw it }
        if (count > 0) queue.put(pcm.copyOf(count))
    }
    override fun close() {
        queue.put(ShortArray(0)); thread.join()
        inner.close()
        failure.get()?.let { throw it }
    }
}

object Mp3 {
    /** Encodes a 16-bit WAV to MP3. */
    fun encode(source: File, target: File, progress: (Float) -> Unit = {}): File {
        val info = Wav.inspect(source)
        try {
            Mp3Sink(target, info.sampleRate, info.channels).use { sink ->
                var done = 0L
                org.openrecorder.app.core.Pcm.read(source) { chunk, _ ->
                    sink.write(ShortArray(chunk.size) { org.openrecorder.app.core.Pcm.toShort(chunk[it]) }, chunk.size)
                    done += chunk.size * 2L; progress(done.toFloat() / info.bytes)
                }
            }
            return target
        } catch (e: Throwable) { target.delete(); throw e }
    }
}

/** ADTS → M4A without re-encoding. */
fun AudioCodec.remuxAdts(source: File, target: File) {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(source.path)
        extractor.selectTrack(0)
        val m = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
        val track = m.addTrack(extractor.getTrackFormat(0))
        m.start()
        val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            m.writeSampleData(track, buffer, info)
            extractor.advance()
        }
        m.stop()
    } catch (e: Throwable) { target.delete(); throw e }
    finally { runCatching { muxer?.release() }; extractor.release() }
}

/** Duration reported by the container, in ms. */
fun AudioCodec.durationMs(file: File): Long = android.media.MediaMetadataRetriever().run {
    try { setDataSource(file.path); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
    catch (e: Exception) { 0L } finally { release() }
}
