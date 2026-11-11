package org.openinkbridge.sdk

import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenInkBridgeViewEraserTest {

    private val view = OpenInkBridgeView(RuntimeEnvironment.getApplication()).apply {
        eraserRadiusPx = 2f
        layout(0, 0, 200, 200)
    }
    private var reported = 0
    private var erased: StrokeErase? = null

    init {
        view.setOnStrokeListener { reported++ }
        view.setOnStrokesErasedListener { erased = it }
    }

    private fun line(y: Float) = (0..20).map { PenPoint(it * 5f, y, 0.5f, 0f, it.toLong()) }

    private val top = DrawingStroke(line(10f), Color.BLACK, 1f, StylusTool.PEN)
    private val bottom = DrawingStroke(line(50f), Color.BLACK, 1f, StylusTool.PEN)

    private fun event(action: Int, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f }
        return MotionEvent.obtain(0, 0, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    /** A vertical swipe at [x] over both strokes. */
    private fun swipe(x: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, 0f, tool))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x, 30f, tool))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x, 60f, tool))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x, 60f, tool))
    }

    private fun nextFrame() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

    @Test
    fun pointEraserCutsTheStrokesItCrosses() {
        view.strokes = listOf(top, bottom)
        view.setStylusTool(StylusTool.ERASER_POINT)

        swipe(50f)

        val change = erased!!
        assertEquals(listOf(top, bottom), change.removed)
        assertEquals(4, change.added.size)
        assertEquals(change.added, view.strokes)
        assertTrue(view.strokes.all { stroke -> stroke.points.none { it.x in 49f..51f } })
        assertEquals(0, reported)
    }

    @Test
    fun strokeEraserRemovesWholeStrokes() {
        view.strokes = listOf(top, bottom)
        view.setStylusTool(StylusTool.ERASER_STROKE)

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 50f, 0f))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 50f, 20f))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 50f, 20f))

        assertEquals(listOf(bottom), view.strokes)
        assertEquals(StrokeErase(listOf(top), emptyList()), erased)
    }

    @Test
    fun theStylusEraserEndRemovesWholeStrokes() {
        view.strokes = listOf(top, bottom)

        swipe(50f, MotionEvent.TOOL_TYPE_ERASER)

        assertTrue(view.strokes.isEmpty())
    }

    @Test
    fun anEraseThatTouchesNothingIsNotReported() {
        view.strokes = listOf(top)
        view.setStylusTool(StylusTool.ERASER_POINT)

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 50f, 100f))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 50f, 120f))

        assertNull(erased)
        assertEquals(listOf(top), view.strokes)
    }

    @Test
    fun leavingTheEraserMidStrokeKeepsErasingUntilPenUp() {
        view.strokes = listOf(top, bottom)
        view.setStylusTool(StylusTool.ERASER_POINT)

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 50f, 0f))
        view.setStylusTool(StylusTool.PEN)
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 50f, 60f))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 50f, 60f))
        nextFrame()

        assertEquals(4, view.strokes.size)
        assertEquals(0, reported)

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0f, 100f))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 10f, 100f))
        assertEquals(1, reported)
        assertEquals(StylusTool.PEN, view.strokes.last().tool)
    }

    @Test
    fun selectingTheEraserMidStrokeKeepsWhatWasDrawn() {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0f, 100f))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 10f, 100f))
        view.setStylusTool(StylusTool.ERASER_POINT)
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20f, 150f))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 30f, 150f))

        assertEquals(1, reported)
        assertEquals(1, view.strokes.size)
        assertEquals(StylusTool.PEN, view.strokes.single().tool)
    }
}
