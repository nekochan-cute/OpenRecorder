package org.openrecorder.app.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10

/**
 * Waveform levels on a decibel scale. Linear peaks make normal speech look flat
 * (a -30 dBFS voice is 3% of full height), so bars are drawn from -54 dBFS to 0 dBFS.
 */
object Levels {
    const val PER_SECOND = 20
    private const val FLOOR_DB = 54f

    fun fromLinear(peak: Float): Float =
        if (peak <= 0f) 0f else ((20 * log10(peak) + FLOOR_DB) / FLOOR_DB).coerceIn(0f, 1f)

    /** One level byte (0–255) per 50 ms of a 16-bit WAV, using the loudest channel. */
    fun read(file: File): ByteArray {
        val info = Wav.inspect(file)
        val framesPerLevel = (info.sampleRate / PER_SECOND).coerceAtLeast(1)
        val out = java.io.ByteArrayOutputStream((info.durationMs / 50 + 1).toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(info.offset)
            val buffer = ByteArray(info.frameBytes * 4096)
            var remaining = info.bytes
            var peak = 0; var frames = 0
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                input.readFully(buffer, 0, count); remaining -= count
                var i = 0
                while (i + info.frameBytes <= count) {
                    for (c in 0 until info.channels) {
                        val j = i + c * 2
                        peak = maxOf(peak, abs(((buffer[j].toInt() and 255) or (buffer[j + 1].toInt() shl 8)).toShort().toInt()))
                    }
                    i += info.frameBytes
                    if (++frames == framesPerLevel) { out.write(Math.round(fromLinear(peak / 32768f) * 255)); peak = 0; frames = 0 }
                }
            }
            if (frames > 0) out.write(Math.round(fromLinear(peak / 32768f) * 255))
        }
        return out.toByteArray()
    }
}
