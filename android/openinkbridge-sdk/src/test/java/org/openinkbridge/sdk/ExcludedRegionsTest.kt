package org.openinkbridge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludedRegionsTest {
    private val box = ExcludedBox(100f, 100f, 200f, 200f)

    private fun p(x: Float, y: Float, t: Long = 0L, pressure: Float = 0.5f) = PenPoint(x, y, pressure, 0f, t)

    @Test
    fun strokeOutsideEveryRegionIsKeptAsIs() {
        val stroke = listOf(p(0f, 0f), p(50f, 50f), p(90f, 20f))
        val pieces = ExcludedRegions.split(stroke, listOf(box))
        assertEquals(1, pieces.size)
        assertSame(stroke, pieces[0])
    }

    @Test
    fun noRegionsKeepsTheStroke() {
        val stroke = listOf(p(150f, 150f), p(160f, 160f))
        assertSame(stroke, ExcludedRegions.split(stroke, emptyList())[0])
    }

    @Test
    fun strokeInsideARegionIsDropped() {
        assertTrue(ExcludedRegions.split(listOf(p(120f, 120f), p(180f, 150f)), listOf(box)).isEmpty())
        assertTrue(ExcludedRegions.split(listOf(p(150f, 150f)), listOf(box)).isEmpty())
    }

    @Test
    fun crossingStrokeIsCutAtTheEdges() {
        val stroke = listOf(p(0f, 150f, t = 0L, pressure = 0f), p(300f, 150f, t = 300L, pressure = 0.3f))
        val pieces = ExcludedRegions.split(stroke, listOf(box))
        assertEquals(2, pieces.size)

        val before = pieces[0]
        assertEquals(2, before.size)
        assertEquals(0f, before[0].x, 0.001f)
        assertEquals(100f, before[1].x, 0.001f)
        assertEquals(100L, before[1].timestamp)
        assertEquals(0.1f, before[1].pressure, 0.001f)

        val after = pieces[1]
        assertEquals(200f, after[0].x, 0.001f)
        assertEquals(200L, after[0].timestamp)
        assertEquals(300f, after.last().x, 0.001f)
    }

    @Test
    fun strokeLeavingARegionStartsAtItsEdge() {
        val pieces = ExcludedRegions.split(listOf(p(150f, 150f), p(150f, 250f), p(150f, 300f)), listOf(box))
        assertEquals(1, pieces.size)
        assertEquals(listOf(200f, 250f, 300f), pieces[0].map { it.y })
    }

    @Test
    fun segmentCrossingTwoRegionsLeavesTheGapBetweenThem() {
        val second = ExcludedBox(250f, 100f, 300f, 200f)
        val pieces = ExcludedRegions.split(listOf(p(0f, 150f), p(400f, 150f)), listOf(box, second))
        assertEquals(listOf(listOf(0f, 100f), listOf(200f, 250f), listOf(300f, 400f)), pieces.map { piece -> piece.map { it.x } })
    }

    @Test
    fun adjacentRegionsCutAsOne() {
        val next = ExcludedBox(200f, 100f, 300f, 200f)
        val pieces = ExcludedRegions.split(listOf(p(0f, 150f), p(400f, 150f)), listOf(box, next))
        assertEquals(listOf(listOf(0f, 100f), listOf(300f, 400f)), pieces.map { piece -> piece.map { it.x } })
    }

    @Test
    fun strokeAlongAnEdgeOutsideIsKept() {
        val stroke = listOf(p(0f, 99f), p(300f, 99f))
        assertSame(stroke, ExcludedRegions.split(stroke, listOf(box))[0])
    }

    @Test
    fun grazingACornerDoesNotCut() {
        val stroke = listOf(p(50f, 150f), p(150f, 50f))
        assertSame(stroke, ExcludedRegions.split(stroke, listOf(box))[0])
    }

    @Test
    fun reenteringStrokeKeepsEachOutsidePart() {
        val stroke = listOf(p(50f, 150f), p(150f, 150f), p(150f, 250f), p(150f, 150f), p(250f, 150f))
        val pieces = ExcludedRegions.split(stroke, listOf(box))
        assertEquals(3, pieces.size)
        assertEquals(listOf(50f, 100f), pieces[0].map { it.x })
        assertEquals(listOf(200f, 250f, 200f), pieces[1].map { it.y })
        assertEquals(listOf(200f, 250f), pieces[2].map { it.x })
    }
}
