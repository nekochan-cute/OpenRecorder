package org.openrecorder.app.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class ProcessOptions(
    val noiseReduction: Boolean = true,
    /** 0 = gentle, 1 = strong. Controls over-subtraction and the attenuation floor. */
    val strength: Float = 0.6f,
    val enhance: Boolean = true,
    val normalize: Boolean = true,
    /** Target loudness of speech (gated RMS), in dBFS. Without [normalize], the source loudness is kept. */
    val targetDb: Float = -18f,
) {
    val any get() = noiseReduction || enhance || normalize
}

/** In-place iterative radix-2 complex FFT. */
class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    init { require(n >= 2 && n and (n - 1) == 0) }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2; val step = n / size
            for (start in 0 until n step size) for (k in 0 until half) {
                val c = cosTable[k * step]; val s = if (inverse) sinTable[k * step] else -sinTable[k * step]
                val a = start + k; val b = a + half
                val tr = re[b] * c - im[b] * s; val ti = re[b] * s + im[b] * c
                re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
            }
            size *= 2
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

/**
 * Streaming spectral-gating noise reducer for one channel. The per-bin noise floor is the
 * minimum of the smoothed power over the last ~2 seconds (minimum statistics), so steady hiss
 * and hum are attenuated while speech, which rises above the floor, passes. Output is
 * length-preserving: the STFT latency is removed internally and [finish] flushes the tail.
 */
class NoiseReducer(sampleRate: Int, strength: Float) {
    private val n = if (sampleRate >= 32000) 1024 else 512
    private val hop = n / 2
    private val bins = n / 2 + 1
    private val fft = Fft(n)
    // Periodic sqrt-Hann for analysis and synthesis sums to one at 50% overlap.
    private val window = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
    private val history = DoubleArray(n)
    private val overlap = DoubleArray(n)
    private val re = DoubleArray(n); private val im = DoubleArray(n)
    private val smoothed = DoubleArray(bins); private val noise = DoubleArray(bins); private val gain = DoubleArray(bins) { 1.0 }
    private val overSubtract = 1.0 + 2.0 * strength.coerceIn(0f, 1f)
    private val floor = 10.0.pow(-(6 + 18 * strength.coerceIn(0f, 1f)) / 20.0)
    private val subFrames = max(1, sampleRate / hop / 4) // 0.25 s sub-windows
    private val subMinima = Array(8) { DoubleArray(bins) { Double.MAX_VALUE } }
    private val currentMin = DoubleArray(bins) { Double.MAX_VALUE }
    private var ring = 0
    private var pending = 0
    private var frames = 0
    private var inputCount = 0L
    private var outputCount = 0L
    private var discard = hop

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        val out = FloatList()
        for (i in 0 until count) push(input[i].toDouble(), out)
        inputCount += count; outputCount += out.size
        return out.toArray()
    }

    /** Flushes the delayed tail so the total output length equals the total input length. */
    fun finish(): FloatArray {
        val out = FloatList()
        while (outputCount + out.size < inputCount) push(0.0, out)
        val keep = (inputCount - outputCount).toInt().coerceAtLeast(0)
        outputCount = inputCount
        return out.toArray().copyOf(keep)
    }

    private fun push(sample: Double, out: FloatList) {
        history[ring] = sample; ring = (ring + 1) % n
        if (++pending < hop) return
        pending = 0
        for (i in 0 until n) { re[i] = history[(ring + i) % n] * window[i]; im[i] = 0.0 }
        fft.transform(re, im)
        for (k in 0 until bins) {
            val power = re[k] * re[k] + im[k] * im[k]
            smoothed[k] = if (frames <= 1) power else 0.7 * smoothed[k] + 0.3 * power
            // The first frame is half zero padding and would pin the minimum near zero.
            if (frames >= 1) currentMin[k] = min(currentMin[k], smoothed[k])
            var m = currentMin[k]
            for (window in subMinima) m = min(m, window[k])
            noise[k] = if (m == Double.MAX_VALUE) 0.0 else MIN_BIAS * m
            val g = max(floor, 1 - overSubtract * noise[k] / (power + 1e-12))
            gain[k] = 0.5 * gain[k] + 0.5 * g
            re[k] *= gain[k]; im[k] *= gain[k]
            if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
        }
        if (++frames % subFrames == 0) {
            val oldest = subMinima[(frames / subFrames) % subMinima.size]
            System.arraycopy(currentMin, 0, oldest, 0, bins); java.util.Arrays.fill(currentMin, Double.MAX_VALUE)
        }
        fft.transform(re, im, inverse = true)
        for (i in 0 until n) overlap[i] += re[i] * window[i]
        for (i in 0 until hop) if (discard > 0) discard-- else out.add(overlap[i].toFloat())
        System.arraycopy(overlap, hop, overlap, 0, n - hop); java.util.Arrays.fill(overlap, n - hop, n, 0.0)
    }

    // The windowed minimum of the smoothed power sits near a third of the mean noise power.
    private companion object { const val MIN_BIAS = 2.5 }
}

/** RBJ biquad, direct form I. */
class Biquad private constructor(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
    companion object {
        fun highPass(rate: Int, hz: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
        fun peaking(rate: Int, hz: Double, db: Double, q: Double = 1.0): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + alpha / a
            return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
        }
    }
}

/** Speech clarity: rumble high-pass, presence lift and a gentle linked compressor. */
class VoiceEnhancer(private val rate: Int, channels: Int) {
    private val highPass = List(channels) { Biquad.highPass(rate, 80.0) }
    private val presence = List(channels) { if (rate > 8000) Biquad.peaking(rate, 3000.0, 4.0) else null }
    private val attack = exp(-1.0 / (0.010 * rate)); private val release = exp(-1.0 / (0.150 * rate))
    private val threshold = 10.0.pow(-24.0 / 20); private val ratio = 3.0
    private var envelope = 0.0

    /** Processes one interleaved frame in place. */
    fun frame(frame: DoubleArray) {
        var level = 0.0
        for (c in frame.indices) {
            var v = highPass[c].process(frame[c])
            presence[c]?.let { v = it.process(v) }
            frame[c] = v; level = max(level, kotlin.math.abs(v))
        }
        envelope = if (level > envelope) attack * envelope + (1 - attack) * level else release * envelope + (1 - release) * level
        if (envelope > threshold) {
            val g = (envelope / threshold).pow(1 / ratio - 1)
            for (c in frame.indices) frame[c] *= g
        }
    }
}

/** Gated loudness measurement in the spirit of EBU R128 (unweighted): absolute -60 dBFS, relative -15 dB gates. */
class LoudnessMeter(rate: Int) {
    private val blockFrames = max(1, rate / 20)
    private val blocks = ArrayList<Double>()
    private var sum = 0.0; private var count = 0
    var peak = 0.0; private set
    fun frame(frame: DoubleArray) {
        var e = 0.0
        for (v in frame) { e += v * v; peak = max(peak, kotlin.math.abs(v)) }
        sum += e / frame.size
        if (++count == blockFrames) { blocks += sum / count; sum = 0.0; count = 0 }
    }
    /** Gated RMS in dBFS, or null for silence. */
    fun loudnessDb(): Double? {
        val absolute = blocks.filter { it > 1e-6 }
        if (absolute.isEmpty()) return null
        val relative = absolute.average() * 10.0.pow(-1.5)
        val gated = absolute.filter { it >= relative }
        return 10 * log10(gated.average())
    }
}

object AudioProcessor {
    /** Maximum boost; quiet recordings are lifted, but noise must not be amplified without limit. */
    const val MAX_GAIN_DB = 24.0
    private const val CEILING = 0.89 // -1 dBFS

    /**
     * Writes a processed copy of a 16-bit WAV. The source is never modified. A first pass measures
     * the loudness of the processed signal; the second applies the gain that reaches the target,
     * or, without normalization, restores the source loudness lost to compression and gating.
     */
    fun process(source: File, target: File, options: ProcessOptions, progress: (Float) -> Unit = {}): WavInfo {
        require(options.any) { "処理内容を選択してください" }
        require(source.canonicalPath != target.canonicalPath)
        val info = Wav.inspect(source)
        require(info.frames > 0) { "音声が空です" }
        try {
            val twoPass = options.normalize || options.enhance
            val gain = if (!twoPass) 1.0 else {
                val processed = LoudnessMeter(info.sampleRate)
                val original = if (options.normalize) null else LoudnessMeter(info.sampleRate)
                run(source, info, options, { progress(0.5f * it) }, original?.let { m -> { frame: DoubleArray -> m.frame(frame) } }) { processed.frame(it) }
                val loudness = processed.loudnessDb()
                val target = if (options.normalize) options.targetDb.toDouble() else original!!.loudnessDb()
                if (loudness == null || target == null) 1.0 else 10.0.pow(min(MAX_GAIN_DB, target - loudness) / 20)
            }
            val start = if (twoPass) 0.5f else 0f
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0); out.write(Wav.header(0, info.sampleRate, info.channels))
                val buffer = java.nio.ByteBuffer.allocate(64 * 1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var written = 0L
                run(source, info, options, { progress(start + (1 - start) * it) }) { frame ->
                    for (v in frame) {
                        buffer.putShort((limit(v * gain) * 32767).toInt().coerceIn(-32768, 32767).toShort())
                        if (!buffer.hasRemaining()) { out.write(buffer.array(), 0, buffer.position()); written += buffer.position(); buffer.clear() }
                    }
                }
                out.write(buffer.array(), 0, buffer.position()); written += buffer.position()
                out.seek(0); out.write(Wav.header(written, info.sampleRate, info.channels)); out.fd.sync()
            }
            progress(1f)
            return Wav.inspect(target)
        } catch (e: Throwable) { target.delete(); throw e }
    }

    /** Soft limiter above -1 dBFS: transparent below the knee, never exceeds full scale. */
    fun limit(v: Double): Double {
        val a = kotlin.math.abs(v)
        if (a <= CEILING) return v
        return kotlin.math.sign(v) * (CEILING + (1 - CEILING) * tanh((a - CEILING) / (1 - CEILING)))
    }

    /** Reads the WAV, runs the selected chain and delivers interleaved frames in order. */
    private fun run(source: File, info: WavInfo, options: ProcessOptions, progress: (Float) -> Unit,
                    raw: ((DoubleArray) -> Unit)? = null, sink: (DoubleArray) -> Unit) {
        val ch = info.channels
        val reducers = if (options.noiseReduction) List(ch) { NoiseReducer(info.sampleRate, options.strength) } else null
        val enhancer = if (options.enhance) VoiceEnhancer(info.sampleRate, ch) else null
        val frame = DoubleArray(ch)
        fun emit(channels: List<FloatArray>) {
            for (f in 0 until channels.minOf { it.size }) {
                for (c in 0 until ch) frame[c] = channels[c][f].toDouble()
                enhancer?.frame(frame)
                sink(frame)
            }
        }
        RandomAccessFile(source, "r").use { input ->
            input.seek(info.offset)
            val bytes = ByteArray(info.frameBytes * 8192)
            var read = 0L
            while (read < info.bytes) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = minOf(bytes.size.toLong(), info.bytes - read).toInt()
                input.readFully(bytes, 0, count); read += count
                val frames = count / info.frameBytes
                val split = List(ch) { c -> FloatArray(frames) { f ->
                    val i = (f * ch + c) * 2
                    ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort() / 32768f
                } }
                if (raw != null) for (f in 0 until frames) { for (c in 0 until ch) frame[c] = split[c][f].toDouble(); raw(frame) }
                emit(reducers?.mapIndexed { c, r -> r.process(split[c]) } ?: split)
                progress(read.toFloat() / info.bytes)
            }
            reducers?.let { r -> emit(r.map { it.finish() }) }
        }
    }
}

/** Minimal growable float buffer, avoiding boxing in hot loops. */
internal class FloatList {
    private var data = FloatArray(1024)
    var size = 0; private set
    fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    fun toArray(): FloatArray = data.copyOf(size)
}
