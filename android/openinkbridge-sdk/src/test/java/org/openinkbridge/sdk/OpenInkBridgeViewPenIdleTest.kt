package org.openinkbridge.sdk

import android.os.Looper
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenInkBridgeViewPenIdleTest {

    private val view = OpenInkBridgeView(RuntimeEnvironment.getApplication()).apply { penIdleDelayMs = 1000L }
    private val ran = mutableListOf<String>()

    private fun pen(action: Int): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 0.5f }
        return MotionEvent.obtain(0, 0, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun runsAtOnceWhenThePenIsIdle() {
        view.runWhenPenIdle { ran += "now" }
        assertEquals(listOf("now"), ran)
    }

    @Test
    fun waitsWhileThePenTouchesAndForTheDelayAfter() {
        view.reportPenActivity(pen(MotionEvent.ACTION_DOWN))
        view.runWhenPenIdle { ran += "a" }
        view.runWhenPenIdle { ran += "b" }
        advance(5000)
        assertEquals(emptyList<String>(), ran)

        view.reportPenActivity(pen(MotionEvent.ACTION_UP))
        advance(900)
        assertEquals(emptyList<String>(), ran)
        advance(200)
        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun aNewStrokeRestartsTheWait() {
        view.reportPenActivity(pen(MotionEvent.ACTION_DOWN))
        view.reportPenActivity(pen(MotionEvent.ACTION_UP))
        view.runWhenPenIdle { ran += "a" }
        advance(600)
        view.reportPenActivity(pen(MotionEvent.ACTION_DOWN))
        view.reportPenActivity(pen(MotionEvent.ACTION_UP))
        advance(600)
        assertEquals(emptyList<String>(), ran)
        advance(500)
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun runsSoonAfterThePenLeavesRange() {
        view.reportPenActivity(pen(MotionEvent.ACTION_DOWN))
        view.reportPenActivity(pen(MotionEvent.ACTION_UP))
        view.runWhenPenIdle { ran += "a" }
        view.reportPenActivity(pen(MotionEvent.ACTION_HOVER_EXIT))
        advance(200)
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun aHoverExitFollowedByATouchIsNotLeavingRange() {
        view.reportPenActivity(pen(MotionEvent.ACTION_DOWN))
        view.reportPenActivity(pen(MotionEvent.ACTION_UP))
        view.runWhenPenIdle { ran += "a" }
        view.reportPenActivity(pen(MotionEvent.ACTION_HOVER_EXIT))
        view.reportPenActivity(pen(MotionEvent.ACTION_DOWN))
        advance(500)
        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun anInkedStrokeIsReportedAndRecordedAtOnce() {
        val points = listOf(PenPoint(0f, 0f, 0.5f, 0f, 0), PenPoint(5f, 5f, 0.5f, 0f, 1))
        var reported = 0
        view.setOnStrokeListener { reported++ }
        view.reportPenActivity(pen(MotionEvent.ACTION_DOWN))
        view.commitInkedStroke(points)

        assertEquals(1, reported)
        assertEquals(listOf(points), view.strokes.map { it.points })
        view.strokes = emptyList()
        assertEquals(0, view.strokes.size)
    }
}
