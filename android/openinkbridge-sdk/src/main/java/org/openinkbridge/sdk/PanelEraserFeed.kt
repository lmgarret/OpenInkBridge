package org.openinkbridge.sdk

import android.view.MotionEvent

/**
 * Turns a host's touch stream into the down/move/up sequence a vendor eraser expects. An erase may
 * start mid-stroke, when a barrel button is pressed after the pen touched down, so the first event
 * seen starts it whatever its action. Points are shifted by the offset given at the start.
 */
internal class PanelEraserFeed(private val sink: (action: Int, x: Int, y: Int) -> Unit) {
    var isErasing = false
        private set
    private var offsetX = 0
    private var offsetY = 0
    private val traced = ArrayList<FloatArray>()

    /** Every point the current or last erase went through, in host coordinates, as (x, y). */
    val trace: List<FloatArray> get() = traced.toList()

    /**
     * Feeds one point of the stroke; [start] supplies the offset when it starts an erase. [fromX],
     * [fromY] is where the event's batch begins (its oldest historical point): an erase starts
     * there, so it covers the same points the app sees.
     */
    fun onTouch(action: Int, x: Float, y: Float, start: () -> Pair<Int, Int>, fromX: Float = x, fromY: Float = y) {
        val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (!isErasing) {
            if (end) return
            val (dx, dy) = start()
            offsetX = dx
            offsetY = dy
            isErasing = true
            traced.clear()
            send(MotionEvent.ACTION_DOWN, fromX, fromY)
            // The firmware erases from the first move on: without this its first circle is left inked.
            sink(MotionEvent.ACTION_MOVE, fromX.toInt() + offsetX, fromY.toInt() + offsetY)
            send(MotionEvent.ACTION_MOVE, x, y)
            return
        }
        if (end) {
            send(MotionEvent.ACTION_UP, x, y)
            isErasing = false
        } else {
            send(MotionEvent.ACTION_MOVE, x, y)
        }
    }

    fun reset() {
        isErasing = false
    }

    private fun send(action: Int, x: Float, y: Float) {
        if (action != MotionEvent.ACTION_UP) traced += floatArrayOf(x.toInt().toFloat(), y.toInt().toFloat())
        sink(action, x.toInt() + offsetX, y.toInt() + offsetY)
    }
}

/**
 * Indices of the historical points of a batch of [history] to send to a panel eraser besides the
 * current one: up to [extra], spread evenly, never the first (an erase starts there anyway).
 */
internal fun batchPicks(history: Int, extra: Int = 2): List<Int> {
    if (history <= 1) return emptyList()
    val count = minOf(extra, history - 1)
    return (1..count).map { k -> k * history / (count + 1) }.distinct().filter { it in 1 until history }
}
