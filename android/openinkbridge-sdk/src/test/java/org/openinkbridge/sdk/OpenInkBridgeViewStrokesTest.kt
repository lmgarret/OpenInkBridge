package org.openinkbridge.sdk

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenInkBridgeViewStrokesTest {

    private val view = OpenInkBridgeView(RuntimeEnvironment.getApplication())
    private var reported = 0

    init {
        view.setOnStrokeListener { reported++ }
    }

    private fun line(y: Float) = listOf(PenPoint(0f, y, 0.5f, 0f, 0), PenPoint(10f, y, 0.5f, 0f, 1))

    private val pen = DrawingStroke(line(1f), Color.BLACK, 3f, StylusTool.PEN)
    private val pencil = DrawingStroke(line(2f), Color.DKGRAY, 2f, StylusTool.PENCIL)

    @Test
    fun assigningStrokesDoesNotReportThem() {
        view.strokes = listOf(pen, pencil)
        assertEquals(0, reported)
    }

    @Test
    fun assignedStrokesKeepTheirPen() {
        view.setStylusTool(StylusTool.PEN)
        view.strokes = listOf(pen, pencil)

        assertEquals(listOf(pen, pencil), view.strokes)
        val svg = view.exportToSvg()
        assertTrue(svg, svg.contains("data-tool=\"pencil\""))
        assertTrue(svg, svg.contains("data-tool=\"pen\""))
    }

    @Test
    fun assignedStrokesReplaceTheCommittedOnes() {
        view.addCompletedStroke(line(5f))
        view.strokes = listOf(pencil)
        assertEquals(listOf(pencil), view.strokes)
    }

    @Test
    fun assigningNoStrokesClears() {
        view.strokes = listOf(pen)
        view.strokes = emptyList()
        val assigned = view.exportToSvg()

        view.strokes = listOf(pen)
        view.clear()

        assertTrue(view.strokes.isEmpty())
        assertEquals(assigned, view.exportToSvg())
    }

    @Test
    fun addCompletedStrokeReportsByDefaultAndKeepsTheGivenPen() {
        view.setStylusTool(StylusTool.PEN)
        view.addCompletedStroke(line(1f))
        view.addCompletedStroke(line(2f), StylusTool.PENCIL, notify = false)

        assertEquals(1, reported)
        assertEquals(listOf(StylusTool.PEN, StylusTool.PENCIL), view.strokes.map { it.tool })
    }
}
