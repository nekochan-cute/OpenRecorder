package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun sampleFile(channels: Int = 1, frames: Int = 16000): File = File.createTempFile("openrec-", ".wav").apply {
        deleteOnExit()
        val pcm = ByteBuffer.allocate(frames*channels*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame -> repeat(channels) { channel -> pcm.putShort((frame+channel*100).toShort()) } }
        outputStream().use { it.write(Wav.header(pcm.capacity().toLong(),16000,channels));it.write(pcm.array()) }
    }
    @Test fun trimPreservesExactSamplesAndOriginal() {
        val source=sampleFile();val original=source.readBytes();val target=File.createTempFile("openrec-trim-", ".wav").apply {deleteOnExit()}
        val info=Wav.edit(source,target,listOf(Span(250,750)))
        assertEquals(500L,info.durationMs);assertEquals(8000L,info.frames)
        RandomAccessFile(target,"r").use {it.seek(info.offset);assertEquals(4000,java.lang.Short.reverseBytes(it.readShort()).toInt())}
        assertArrayEquals(original,source.readBytes())
    }
    @Test fun removalJoinsBothSidesOnFrameBoundaries() {
        val source=sampleFile(2);val target=File.createTempFile("openrec-cut-", ".wav").apply {deleteOnExit()}
        val info=Wav.edit(source,target,Timeline.remove(250,750,1000))
        assertEquals(500L,info.durationMs);assertEquals(2,info.channels)
        RandomAccessFile(target,"r").use {it.seek(info.offset+4000*4);assertEquals(12000,java.lang.Short.reverseBytes(it.readShort()).toInt());assertEquals(12100,java.lang.Short.reverseBytes(it.readShort()).toInt())}
    }
    @Test fun crashRepairCountsOnlyCompleteStereoFrames() {
        val source=sampleFile(2)
        RandomAccessFile(source,"rw").use {it.seek(0);it.write(Wav.header(0,16000,2));it.seek(it.length());it.writeByte(1)}
        val info=Wav.repair(source,16000,2)
        assertEquals(1000L,info.durationMs);assertEquals(64044L,source.length())
    }
    @Test fun readsExtraRiffChunk() {
        val source=sampleFile();val original=source.readBytes()
        val buffer=ByteBuffer.allocate(original.size+12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(original,0,36);buffer.put("JUNK".toByteArray());buffer.putInt(4);buffer.putInt(0);buffer.put(original,36,original.size-36)
        buffer.putInt(4,buffer.capacity()-8)
        source.writeBytes(buffer.array());assertEquals(56L,Wav.inspect(source).offset)
    }
    @Test fun rejectsTruncatedDataWithoutAllocatingItsClaimedSize() {
        val f=sampleFile();RandomAccessFile(f,"rw").use {it.setLength(70)}
        assertThrows(IllegalArgumentException::class.java) {Wav.inspect(f)}
    }
    @Test fun sameFileEditCannotDestroySource() {
        val f=sampleFile();val original=f.readBytes()
        assertThrows(IllegalArgumentException::class.java) {Wav.edit(f,f,listOf(Span(0,500)))}
        assertArrayEquals(original,f.readBytes())
    }
    @Test fun computesBoundedWaveformIncludingNegativeFullScale() {
        val f=sampleFile();RandomAccessFile(f,"rw").use {it.seek(44);it.writeShort(0x0080)}
        val peaks=Wav.peaks(f,20)
        assertEquals(20,peaks.size);assertEquals(1f,peaks[0],0.0001f);assertTrue(peaks.all {it in 0f..1f})
    }
}
