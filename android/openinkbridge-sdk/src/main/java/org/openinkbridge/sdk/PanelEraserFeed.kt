package org.openinkbridge.sdk

import android.view.MotionEvent

/**
 * Turns a host's touch stream into the down/move/up sequence a vendor eraser expects. An erase may
 * start mid-stroke, when a barrel button is pressed after the pen touched down, so the first event
 * seen starts it whatever its action. Points are shifted by the offset given at the start.
 */
internal class PanelEraserFeed(
    private val sink: (action: Int, x: Int, y: Int) -> Unit,
    private val retrace: (points: List<IntArray>) -> Unit = {}
) {
    var isErasing = false
        private set
    private var offsetX = 0
    private var offsetY = 0
    private var startTime = 0L
    private val early = ArrayList<IntArray>()
    private val traced = ArrayList<FloatArray>()

    /** Every point the current or last erase went through, in host coordinates, as (x, y). */
    val trace: List<FloatArray> get() = traced.toList()

    /** Counts erases, so a replay scheduled for one never runs over the next. */
    var generation = 0
        private set

    /**
     * Returns true when this event starts an erase; [start] supplies its offset then. [fromX],
     * [fromY] is where the event's batch begins (its oldest historical point): an erase starts
     * there, so it covers the same points the app sees.
     */
    fun onTouch(
        action: Int,
        x: Float,
        y: Float,
        start: () -> Pair<Int, Int>,
        fromX: Float = x,
        fromY: Float = y,
        time: Long = 0L
    ): Boolean {
        val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (!isErasing) {
            if (end) return false
            val (dx, dy) = start()
            offsetX = dx
            offsetY = dy
            isErasing = true
            generation++
            startTime = time
            early.clear()
            traced.clear()
            send(MotionEvent.ACTION_DOWN, fromX, fromY, early = true)
            // The firmware erases from the first move on: without this its first circle is left inked.
            sink(MotionEvent.ACTION_MOVE, fromX.toInt() + offsetX, fromY.toInt() + offsetY)
            send(MotionEvent.ACTION_MOVE, x, y, early = true)
            return true
        }
        if (end) {
            send(MotionEvent.ACTION_UP, x, y)
            isErasing = false
        } else {
            send(MotionEvent.ACTION_MOVE, x, y, early = time - startTime <= EARLY_MS)
        }
        return false
    }

    /**
     * Traces the erase's first [EARLY_MS] again, apart from the live erase: moving the live eraser
     * back there would erase along the jumps too. The vendor ink of the stroke the erase
     * interrupted keeps landing for a while after the pause, over what was erased. Does nothing if
     * another erase started since [generation].
     */
    fun replayStart(generation: Int) {
        if (generation != this.generation || early.isEmpty()) return
        retrace(early.toList())
    }

    fun reset() {
        isErasing = false
    }

    private fun send(action: Int, x: Float, y: Float, early: Boolean = false) {
        if (action != MotionEvent.ACTION_UP) traced += floatArrayOf(x.toInt().toFloat(), y.toInt().toFloat())
        val p = intArrayOf(x.toInt() + offsetX, y.toInt() + offsetY)
        if (early) this.early += p
        sink(action, p[0], p[1])
    }

    companion object {
        /** How long the interrupted vendor ink keeps landing; a little more than measured. */
        const val EARLY_MS = 250L
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
