package org.openinkbridge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeEraserTest {
    private fun stroke(vararg xs: Float, width: Float = 0f) =
        DrawingStroke(xs.mapIndexed { i, x -> PenPoint(x, 0f, 1f, 0f, i * 10L) }, 0, width)

    private val vertical = listOf(floatArrayOf(50f, -10f), floatArrayOf(50f, 10f))

    @Test
    fun cutsAStrokeInTwoWhereThePathCrossesIt() {
        val after = StrokeEraser.erase(listOf(stroke(0f, 40f, 48f, 52f, 60f, 100f)), vertical, 5f, 1f)!!

        assertEquals(2, after.size)
        assertEquals(listOf(0f, 40f), after[0].points.dropLast(1).map { it.x })
        assertEquals(45f, after[0].points.last().x, EDGE)
        assertEquals(55f, after[1].points.first().x, EDGE)
        assertEquals(listOf(60f, 100f), after[1].points.drop(1).map { it.x })
    }

    @Test
    fun cutEndsInterpolateTheStroke() {
        val piece = StrokeEraser.erase(listOf(stroke(0f, 48f)), vertical, 5f, 1f)!!.single()

        val end = piece.points.last()
        assertEquals(45f, end.x, EDGE)
        assertEquals(end.x / 48f * 10f, end.timestamp.toFloat(), 1f)
    }

    @Test
    fun widerStrokesAreCutAQuarterOfTheirHalfWidthFurther() {
        val piece = StrokeEraser.erase(listOf(stroke(0f, 48f, width = 8f)), vertical, 5f, 1f)!!.single()

        assertEquals(44f, piece.points.last().x, EDGE)
    }

    @Test
    fun dropsPiecesTooShortToDraw() {
        assertEquals(emptyList<DrawingStroke>(), StrokeEraser.erase(listOf(stroke(49f, 51f)), vertical, 5f, 1f))
    }

    @Test
    fun returnsNullWhenNothingIsTouched() {
        assertNull(StrokeEraser.erase(listOf(stroke(0f, 20f)), vertical, 5f, 1f))
        assertNull(StrokeEraser.erase(emptyList(), vertical, 5f, 1f))
        assertNull(StrokeEraser.erase(listOf(stroke(0f, 100f)), emptyList(), 5f, 1f))
    }

    @Test
    fun aLonePointErasesItsCircle() {
        val after = StrokeEraser.erase(listOf(stroke(0f, 30f, 50f, 70f, 100f)), listOf(floatArrayOf(50f, 0f)), 5f, 1f)!!

        assertEquals(2, after.size)
    }

    @Test
    fun wholeStrokesGoEntirely() {
        val crossed = stroke(0f, 50f, 100f)
        val untouched = DrawingStroke(listOf(PenPoint(0f, 50f, 1f, 0f, 0), PenPoint(100f, 50f, 1f, 0f, 1)), 0, 0f)

        assertEquals(listOf(untouched), StrokeEraser.erase(listOf(crossed, untouched), vertical, 5f, 1f, wholeStrokes = true))
    }

    @Test
    fun keepsEachStrokesPen() {
        val pencil = stroke(0f, 30f, 50f, 70f, 100f).copy(tool = StylusTool.PENCIL, color = 7, width = 2f)

        val after = StrokeEraser.erase(listOf(pencil), vertical, 5f, 1f)!!

        assertTrue(after.all { it.tool == StylusTool.PENCIL && it.color == 7 && it.width == 2f })
    }

    @Test
    fun findsSegmentsAtNegativeCoordinates() {
        val after = StrokeEraser.erase(listOf(stroke(-100f, -50f, 0f)), listOf(floatArrayOf(-50f, -10f), floatArrayOf(-50f, 10f)), 5f, 1f)!!

        assertEquals(2, after.size)
    }

    private companion object {
        /** The edge is found to within 1/256 of the segment it lies on. */
        const val EDGE = 0.25f
    }
}
