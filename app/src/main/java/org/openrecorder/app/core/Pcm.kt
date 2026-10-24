package org.openrecorder.app.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil
import kotlin.math.max

/**
 * Streaming linear resampler with a box low-pass sized to the decimation ratio. Good enough for
 * speech models and for joining recordings of different rates; not a mastering-grade converter.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val taps = if (inRate > outRate) max(1, ceil(step).toInt()) else 1
    private val history = FloatArray(taps)
    private var historyPos = 0
    private var historySum = 0f
    private var position = 0.0
    private var previous = 0f

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (inRate == outRate) return input.copyOf(count)
        val filtered = FloatArray(count)
        for (i in 0 until count) {
            historySum += input[i] - history[historyPos]
            history[historyPos] = input[i]
            historyPos = (historyPos + 1) % taps
            filtered[i] = historySum / taps
        }
        val out = FloatArray(max(0, ceil((count - position) / step).toInt()))
        var n = 0
        while (position < count && n < out.size) {
            val idx = position.toInt()
            val frac = (position - idx).toFloat()
            val a = if (idx == 0) previous else filtered[idx - 1]
            out[n++] = a + (filtered[idx] - a) * frac
            position += step
        }
        position -= count
        if (count > 0) previous = filtered[count - 1]
        return if (n == out.size) out else out.copyOf(n)
    }
}

object Pcm {
    /** Reads a 16-bit WAV in chunks of about [chunkFrames] frames, calling [block] with interleaved samples in [-1, 1]. */
    fun read(file: File, chunkFrames: Int = 8192, block: (FloatArray, WavInfo) -> Unit) {
        val info = Wav.inspect(file)
        RandomAccessFile(file, "r").use { input ->
            input.seek(info.offset)
            val bytes = ByteArray(info.frameBytes * chunkFrames)
            var read = 0L
            while (read < info.bytes) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = minOf(bytes.size.toLong(), info.bytes - read).toInt()
                input.readFully(bytes, 0, count); read += count
                block(FloatArray(count / 2) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }, info)
            }
        }
    }

    fun mono(interleaved: FloatArray, channels: Int): FloatArray =
        if (channels == 1) interleaved else FloatArray(interleaved.size / channels) { f -> var s = 0f; for (c in 0 until channels) s += interleaved[f * channels + c]; s / channels }

    /** Mono 16 kHz samples for speech models, delivered in chunks. */
    fun speech(file: File, block: (FloatArray, Float) -> Unit) {
        var resampler: Resampler? = null
        var done = 0L
        read(file) { chunk, info ->
            val r = resampler ?: Resampler(info.sampleRate, 16000).also { resampler = it }
            done += chunk.size * 2L
            block(r.process(mono(chunk, info.channels)), done.toFloat() / info.bytes)
        }
    }

    fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}

/** Joins recordings end to end, converting each to the output rate and channel count. */
object Concat {
    fun join(sources: List<File>, target: File, rate: Int, channels: Int, progress: (Float) -> Unit = {}): WavInfo {
        require(sources.size >= 2) { "2件以上の録音を選択してください" }
        require(sources.none { it.canonicalPath == target.canonicalPath })
        val infos = sources.map { Wav.inspect(it) }
        val total = infos.sumOf { it.bytes }.coerceAtLeast(1)
        try {
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0); out.write(Wav.header(0, rate, channels))
                var written = 0L; var done = 0L
                val buffer = java.nio.ByteBuffer.allocate(64 * 1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                fun flush() { out.write(buffer.array(), 0, buffer.position()); written += buffer.position(); buffer.clear() }
                for ((file, info) in sources.zip(infos)) {
                    val resamplers = List(info.channels) { Resampler(info.sampleRate, rate) }
                    Pcm.read(file) { chunk, _ ->
                        val frames = chunk.size / info.channels
                        val perChannel = List(info.channels) { c -> resamplers[c].process(FloatArray(frames) { chunk[it * info.channels + c] }) }
                        val outFrames = perChannel.minOf { it.size }
                        for (f in 0 until outFrames) for (c in 0 until channels) {
                            val v = when {
                                info.channels == channels -> perChannel[c][f]
                                channels == 1 -> perChannel.sumOf { it[f].toDouble() }.toFloat() / info.channels
                                else -> perChannel[minOf(c, info.channels - 1)][f]
                            }
                            buffer.putShort(Pcm.toShort(v))
                            if (!buffer.hasRemaining()) flush()
                        }
                        done += chunk.size * 2L
                        progress(done.toFloat() / total)
                    }
                }
                flush()
                require(written in 1..Wav.MAX_BYTES) { "結合後の音声が長すぎます" }
                out.seek(0); out.write(Wav.header(written, rate, channels)); out.fd.sync()
            }
            return Wav.inspect(target)
        } catch (e: Throwable) { target.delete(); throw e }
    }

    /** Start of each source in the joined timeline, in ms. */
    fun offsets(durations: List<Long>): List<Long> = durations.runningFold(0L) { acc, d -> acc + d }.dropLast(1)
}
