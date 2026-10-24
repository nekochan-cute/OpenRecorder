package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    private val rate = 16000

    private fun wav(samples: FloatArray, channels: Int = 1): File = File.createTempFile("openrec-dsp-", ".wav").apply {
        deleteOnExit()
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        outputStream().use { it.write(Wav.header(pcm.capacity().toLong(), rate, channels)); it.write(pcm.array()) }
    }

    private fun read(file: File): FloatArray {
        val info = Wav.inspect(file)
        val bytes = ByteArray(info.bytes.toInt())
        RandomAccessFile(file, "r").use { it.seek(info.offset); it.readFully(bytes) }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 2) { b.short / 32768f }
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
    private fun db(v: Double) = 20 * log10(v)
    private fun noise(n: Int, level: Float, seed: Long = 7) = java.util.Random(seed).let { r -> FloatArray(n) { (r.nextGaussian() * level).toFloat() } }

    @Test fun fftRoundTripIsLossless() {
        val fft = Fft(64)
        val re = DoubleArray(64) { sin(it * 0.3) + it % 5 }; val im = DoubleArray(64); val original = re.copyOf()
        fft.transform(re, im); fft.transform(re, im, inverse = true)
        original.indices.forEach { assertEquals(original[it], re[it], 1e-9); assertEquals(0.0, im[it], 1e-9) }
    }

    @Test fun noiseReducerPreservesLengthAndAlignment() {
        val input = FloatArray(10_007) { if (it == 5000) 0.9f else 0f }
        val reducer = NoiseReducer(rate, 0f)
        val out = reducer.process(input.copyOfRange(0, 3001)) + reducer.process(input.copyOfRange(3001, input.size)) + reducer.finish()
        assertEquals(input.size, out.size)
        assertEquals(5000, out.indices.maxByOrNull { abs(out[it]) })
    }

    @Test fun noiseReductionLowersSteadyNoiseAndKeepsSpeechBursts() {
        // Two seconds of hiss, then a tone burst over the same hiss.
        val n = rate * 4
        val hiss = noise(n, 0.02f)
        val signal = FloatArray(n) { i -> hiss[i] + if (i >= rate * 2 && i < rate * 3) (0.4 * sin(2 * PI * 440 * i / rate)).toFloat() else 0f }
        val out = process(signal, ProcessOptions(noiseReduction = true, strength = 0.8f, enhance = false, normalize = false))
        val noiseBefore = rms(signal, rate, rate * 2); val noiseAfter = rms(out, rate, rate * 2)
        val toneBefore = rms(signal, rate * 2 + 2000, rate * 3 - 2000); val toneAfter = rms(out, rate * 2 + 2000, rate * 3 - 2000)
        assertTrue("noise reduced by ${db(noiseBefore / noiseAfter)} dB", db(noiseBefore / noiseAfter) > 10)
        assertTrue("tone kept within ${db(toneBefore / toneAfter)} dB", abs(db(toneBefore / toneAfter)) < 2)
    }

    private var lastOutput: File? = null
    private fun process(input: FloatArray, options: ProcessOptions, channels: Int = 1): FloatArray {
        val target = File.createTempFile("openrec-out-", ".wav").apply { deleteOnExit() }
        val source = wav(input, channels); val original = source.readBytes()
        AudioProcessor.process(source, target, options)
        assertArrayEquals(original, source.readBytes())
        lastOutput = target
        return read(target)
    }

    @Test fun normalizationRaisesQuietSpeechToTargetWithoutClipping() {
        val quiet = FloatArray(rate * 3) { i -> (0.02 * sin(2 * PI * 300 * i / rate)).toFloat() }
        val out = process(quiet, ProcessOptions(noiseReduction = false, enhance = false, normalize = true, targetDb = -18f))
        assertEquals(quiet.size, out.size)
        assertEquals(-18.0, db(rms(out)), 1.0)
        assertTrue(out.all { abs(it) <= 1f })
    }

    @Test fun normalizationGainIsCapped() {
        val nearSilent = FloatArray(rate * 2) { i -> (0.003 * sin(2 * PI * 300 * i / rate)).toFloat() }
        val out = process(nearSilent, ProcessOptions(noiseReduction = false, enhance = false, normalize = true))
        assertEquals(AudioProcessor.MAX_GAIN_DB, db(rms(out) / rms(nearSilent)), 0.6)
    }

    @Test fun limiterNeverExceedsFullScale() {
        listOf(0.5, 0.89, 0.95, 1.5, 10.0, -3.0).forEach { assertTrue(abs(AudioProcessor.limit(it)) <= 1.0) }
        assertEquals(0.5, AudioProcessor.limit(0.5), 0.0)
    }

    /** Amplitude of one frequency (Goertzel). */
    private fun level(x: FloatArray, hz: Double, from: Int, to: Int): Double {
        val w = 2 * PI * hz / rate; var s1 = 0.0; var s2 = 0.0
        for (i in from until to) { val s = x[i] + 2 * kotlin.math.cos(w) * s1 - s2; s2 = s1; s1 = s }
        return sqrt(s1 * s1 + s2 * s2 - 2 * kotlin.math.cos(w) * s1 * s2) / (to - from)
    }

    @Test fun enhancementRemovesRumbleRelativeToVoiceAndKeepsLoudness() {
        val input = FloatArray(rate * 2) { i -> (0.2 * sin(2 * PI * 30 * i / rate) + 0.1 * sin(2 * PI * 1000 * i / rate)).toFloat() }
        val out = process(input, ProcessOptions(noiseReduction = false, enhance = true, normalize = false))
        val before = level(input, 30.0, rate, 2 * rate) / level(input, 1000.0, rate, 2 * rate)
        val after = level(out, 30.0, rate, 2 * rate) / level(out, 1000.0, rate, 2 * rate)
        assertTrue("rumble/voice ${db(before / after)} dB", db(before / after) > 12)
        assertEquals(db(rms(input, rate)), db(rms(out, rate)), 1.5)
    }

    @Test fun stereoChannelsStayIndependentAndInterleaved() {
        val frames = rate * 2
        // Speech-like bursts: a steady tone would rightly be treated as noise by minimum statistics.
        val stereo = FloatArray(frames * 2) { i -> val f = i / 2; if (i % 2 == 0 && f / 4800 % 2 == 0) (0.3 * sin(2 * PI * 500 * f / rate)).toFloat() else 0f }
        val out = process(stereo, ProcessOptions(noiseReduction = true, strength = 0.3f, enhance = true, normalize = false), channels = 2)
        assertEquals(stereo.size, out.size)
        val left = FloatArray(frames) { out[it * 2] }; val right = FloatArray(frames) { out[it * 2 + 1] }
        assertTrue("left ${rms(left)}", rms(left) > 0.1); assertTrue("right ${rms(right)}", rms(right) < 0.001)
    }

    @Test fun rejectsEmptyOptionsAndSameFile() {
        val f = wav(FloatArray(1600))
        assertThrows(IllegalArgumentException::class.java) { AudioProcessor.process(f, File.createTempFile("x", ".wav"), ProcessOptions(false, 0f, false, false)) }
        assertThrows(IllegalArgumentException::class.java) { AudioProcessor.process(f, f, ProcessOptions()) }
    }
}
