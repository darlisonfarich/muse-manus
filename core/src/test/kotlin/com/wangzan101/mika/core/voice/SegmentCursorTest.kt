package com.wangzan101.mika.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCursorTest {
    private fun seg(n: Int) = ByteArray(n) { n.toByte() }

    @Test fun deliversOnlyNewSegmentsInOrder() {
        val c = SegmentCursor()
        val a = seg(1); val b = seg(2); val d = seg(3)
        var u = c.update("t1", listOf(a))
        assertFalse(u.reset); assertEquals(listOf(a), u.fresh)
        u = c.update("t1", listOf(a))
        assertTrue(u.fresh.isEmpty())
        u = c.update("t1", listOf(a, b, d))
        assertEquals(listOf(b, d), u.fresh)
    }

    @Test fun nullTurnAfterPlayingIsReset() {
        val c = SegmentCursor()
        c.update("t1", listOf(seg(1)))
        val u = c.update(null, emptyList())
        assertTrue(u.reset); assertTrue(u.fresh.isEmpty())
        assertFalse(c.update(null, emptyList()).reset)
    }

    @Test fun differentTurnResetsAndDeliversFromZero() {
        val c = SegmentCursor()
        c.update("t1", listOf(seg(1), seg(2)))
        val x = seg(5)
        val u = c.update("t2", listOf(x))
        assertTrue(u.reset); assertEquals(listOf(x), u.fresh)
    }

    @Test fun firstEverTurnIsNotAReset() {
        val c = SegmentCursor()
        val u = c.update("t1", emptyList())
        assertFalse(u.reset); assertTrue(u.fresh.isEmpty())
    }

    @Test fun shrinkingListMeansStaleStream() {
        val c = SegmentCursor()
        c.update("t1", listOf(seg(1), seg(2)))
        val u = c.update("t1", listOf(seg(9)))
        assertTrue(u.reset); assertEquals(1, u.fresh.size)
    }
}
