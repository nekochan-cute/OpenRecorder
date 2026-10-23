package org.openrecorder.app.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class WavInfo(val sampleRate: Int, val channels: Int, val offset: Long, val bytes: Long) {
    val frameBytes get() = channels * 2
    val frames get() = bytes / frameBytes
    val durationMs get() = frames * 1000 / sampleRate
}

object Wav {
    const val MAX_BYTES = 0xFFFFFF00L
    fun header(dataBytes: Long, rate: Int, channels: Int): ByteArray {
        require(dataBytes in 0..MAX_BYTES && rate in 8000..192000 && channels in 1..2)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }
    fun repair(file: File, rate: Int, channels: Int): WavInfo {
        require(file.length() >= 44)
        RandomAccessFile(file, "rw").use { out ->
            val bytes = (out.length() - 44).coerceAtMost(MAX_BYTES) / (channels * 2) * (channels * 2)
            out.setLength(bytes + 44); out.seek(0); out.write(header(bytes, rate, channels)); out.fd.sync()
        }
        return inspect(file)
    }
    fun inspect(file: File): WavInfo = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 44) { "WAVが短すぎます" }
        fun tag() = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun uint() = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
        fun ushort() = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xFFFF
        require(tag() == "RIFF"); uint(); require(tag() == "WAVE")
        var rate = 0; var channels = 0
        while (input.filePointer + 8 <= input.length()) {
            val id = tag(); val size = uint(); val start = input.filePointer
            require(size <= input.length() - start) { "WAVのチャンクが破損しています" }
            when (id) {
                "fmt " -> {
                    require(size >= 16 && ushort() == 1) { "16bit PCM WAVを選択してください" }
                    channels = ushort(); rate = uint().toInt(); uint(); ushort()
                    require(ushort() == 16 && channels in 1..2 && rate in 8000..192000)
                }
                "data" -> {
                    require(rate > 0 && size % (channels * 2) == 0L)
                    return@use WavInfo(rate, channels, start, size)
                }
            }
            input.seek(start + size + size % 2)
        }
        error("WAV音声データがありません")
    }
    fun edit(source: File, target: File, spans: List<Span>): WavInfo {
        val info = inspect(source); Timeline.validate(spans, info.durationMs)
        require(source.canonicalPath != target.canonicalPath)
        val frameSpans = spans.map { s ->
            val start = s.startMs * info.sampleRate / 1000
            val end = if (s.endMs == info.durationMs) info.frames else s.endMs * info.sampleRate / 1000
            start to end
        }
        val total = frameSpans.sumOf { (a,b) -> (b-a) * info.frameBytes }
        require(total > 0 && total <= MAX_BYTES)
        try {
            RandomAccessFile(source, "r").use { input ->
                RandomAccessFile(target, "rw").use { output ->
                    output.setLength(0); output.write(header(total, info.sampleRate, info.channels))
                    val buffer = ByteArray(64 * 1024)
                    for ((a,b) in frameSpans) {
                        input.seek(info.offset + a * info.frameBytes)
                        var remaining = (b - a) * info.frameBytes
                        while (remaining > 0) {
                            val count = minOf(remaining, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count); output.write(buffer, 0, count); remaining -= count
                        }
                    }
                    output.fd.sync()
                }
            }
            return inspect(target)
        } catch (e: Exception) { target.delete(); throw e }
    }
    fun peaks(file: File, bins: Int = 240): List<Float> {
        val info = inspect(file)
        if (info.frames == 0L) return emptyList()
        val result = FloatArray(bins)
        RandomAccessFile(file, "r").use { input ->
            input.seek(info.offset)
            val buffer = ByteArray(64 * 1024)
            var bytesRead = 0L
            while (bytesRead < info.bytes) {
                val count = minOf(buffer.size.toLong(), info.bytes - bytesRead).toInt()
                input.readFully(buffer, 0, count)
                for (i in 0 until count - 1 step 2) {
                    val sample = ((buffer[i].toInt() and 255) or (buffer[i+1].toInt() shl 8)).toShort().toInt()
                    val index = ((bytesRead + i) * bins / info.bytes).toInt().coerceIn(0, bins-1)
                    result[index] = maxOf(result[index], abs(sample) / 32768f)
                }
                bytesRead += count
            }
        }
        return result.toList()
    }
}
