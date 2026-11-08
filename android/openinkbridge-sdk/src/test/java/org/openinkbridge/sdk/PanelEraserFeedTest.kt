package org.openinkbridge.sdk

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelEraserFeedTest {
    private val calls = mutableListOf<Triple<Int, Int, Int>>()
    private val feed = PanelEraserFeed { a, x, y -> calls += Triple(a, x, y) }
    private val noOffset = { 0 to 0 }

    @Test
    fun forwardsAStrokeAsDownMoveUpErasingTheFirstPointToo() {
        feed.onTouch(ACTION_DOWN, 1f, 2f, noOffset)
        feed.onTouch(ACTION_MOVE, 3f, 4f, noOffset)
        feed.onTouch(ACTION_UP, 5f, 6f, noOffset)

        assertEquals(listOf(Triple(ACTION_DOWN, 1, 2), Triple(ACTION_MOVE, 1, 2), Triple(ACTION_MOVE, 1, 2), Triple(ACTION_MOVE, 3, 4), Triple(ACTION_UP, 5, 6)), calls)
        assertFalse(feed.isErasing)
    }

    @Test
    fun anEraseStartedMidStrokeBeginsWithDown() {
        feed.onTouch(ACTION_MOVE, 3f, 4f, noOffset)

        assertEquals(listOf(Triple(ACTION_DOWN, 3, 4), Triple(ACTION_MOVE, 3, 4), Triple(ACTION_MOVE, 3, 4)), calls)
        assertTrue(feed.isErasing)
    }

    @Test
    fun anEraseStartsWhereTheEventsBatchBegins() {
        feed.onTouch(ACTION_MOVE, 9f, 9f, noOffset, fromX = 1f, fromY = 2f)

        assertEquals(listOf(Triple(ACTION_DOWN, 1, 2), Triple(ACTION_MOVE, 1, 2), Triple(ACTION_MOVE, 9, 9)), calls)
    }

    @Test
    fun shiftsEveryPointByTheOffsetTakenAtTheStart() {
        var asked = 0
        val start = { asked++; 100 to 200 }
        feed.onTouch(ACTION_DOWN, 1f, 2f, start)
        feed.onTouch(ACTION_MOVE, 3f, 4f, start)
        feed.onTouch(ACTION_CANCEL, 5f, 6f, start)

        assertEquals(1, asked)
        assertEquals(listOf(Triple(ACTION_DOWN, 101, 202), Triple(ACTION_MOVE, 101, 202), Triple(ACTION_MOVE, 101, 202), Triple(ACTION_MOVE, 103, 204), Triple(ACTION_UP, 105, 206)), calls)
    }

    @Test
    fun traceHoldsThePointsSentInHostCoordinates() {
        val offset = { 100 to 200 }
        feed.onTouch(ACTION_MOVE, 3f, 4f, offset, fromX = 1f, fromY = 2f)
        feed.onTouch(ACTION_MOVE, 5f, 6f, offset)
        feed.onTouch(ACTION_UP, 7f, 8f, offset)

        assertEquals(listOf(1f to 2f, 3f to 4f, 5f to 6f), feed.trace.map { it[0] to it[1] })
    }

    @Test
    fun batchPicksSpreadAFewPointsAndSkipTheFirst() {
        assertEquals(emptyList<Int>(), batchPicks(1))
        assertEquals(listOf(1), batchPicks(2))
        assertEquals(listOf(3, 6), batchPicks(9))
    }

    @Test
    fun anEndWithoutAnEraseIsIgnored() {
        feed.onTouch(ACTION_UP, 1f, 2f, noOffset)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun resetStartsTheNextEventAfresh() {
        feed.onTouch(ACTION_DOWN, 1f, 2f, noOffset)
        feed.reset()
        feed.onTouch(ACTION_MOVE, 3f, 4f, noOffset)

        assertEquals(Triple(ACTION_DOWN, 3, 4), calls[calls.size - 3])
    }
}
