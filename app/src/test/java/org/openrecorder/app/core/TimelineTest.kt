package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    private val spans=listOf(Span(0,1000),Span(2000,3000))
    @Test fun markersInDeletedRegionAreRemovedAndLaterMarkersShift() {
        val result=Timeline.marks(listOf(Mark(timeMs=500,text="a"),Mark(timeMs=1500,text="deleted"),Mark(timeMs=2500,text="b")),spans)
        assertEquals(listOf(500L,1500L),result.map {it.timeMs})
        assertEquals(listOf("a","b"),result.map {it.text})
    }
    @Test fun cutBoundaryBelongsOnlyToFollowingKeptInterval() {
        assertNull(Timeline.position(1000,spans));assertEquals(1000L,Timeline.position(2000,spans));assertNull(Timeline.position(3000,spans))
    }
    @Test fun cutSentencesAreDroppedInsteadOfClaimingUnheardWords() {
        val segments=listOf(Segment(500,1500,"crossing"),Segment(2200,2500,"kept"))
        assertEquals(listOf(Segment(1200,1500,"kept")),Timeline.segments(segments,spans))
    }
    @Test fun fullRemovalIsRejected() {assertThrows(IllegalArgumentException::class.java) {Timeline.remove(0,3000,3000)}}
    @Test fun overlappingEditsAreRejected() {assertThrows(IllegalArgumentException::class.java) {Timeline.validate(listOf(Span(0,2000),Span(1000,3000)),3000)}}
    @Test fun subtitleOutputIsLocaleIndependentAndPreservesMilliseconds() {
        val old=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("1\n00:01:02,345 --> 00:01:03,567\n[A] hello\n",subtitles(listOf(Segment(62345,63567,"hello","A"))))
        } finally {java.util.Locale.setDefault(old)}
    }
}
