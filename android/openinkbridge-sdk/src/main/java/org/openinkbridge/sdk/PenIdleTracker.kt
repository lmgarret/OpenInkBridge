package org.openinkbridge.sdk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent

/**
 * Queues actions until the pen is idle: no stroke for [idleDelayMs], or the pen out of range.
 * Actions run on the main thread, in the order they were queued.
 */
internal class PenIdleTracker(var idleDelayMs: Long = 2000L) {
    private val actions = ArrayDeque<() -> Unit>()
    // A View only runs posted callbacks while attached; idle actions must not wait for that.
    private val handler = Handler(Looper.getMainLooper())
    private var touching = false
    // Far enough in the past that a pen that never touched counts as idle.
    private var lastActivity = Long.MIN_VALUE / 2
    private val idleCheck = Runnable { if (isIdle()) runAll() else schedule() }
    private val leftCheck = Runnable { if (!touching) runAll() }

    fun runWhenIdle(action: () -> Unit) {
        if (actions.isEmpty() && isIdle()) {
            action()
            return
        }
        actions.addLast(action)
        schedule()
    }

    /** Tracks pen-down, pen-up and the pen leaving range, from whichever path sees the stylus. */
    fun onPenEvent(event: MotionEvent) {
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                lastActivity = SystemClock.uptimeMillis()
                handler.removeCallbacks(idleCheck)
                handler.removeCallbacks(leftCheck)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                lastActivity = SystemClock.uptimeMillis()
                schedule()
            }
            // Hover also ends just before the tip touches; only a hover exit with no touch
            // following it means the pen left range.
            MotionEvent.ACTION_HOVER_EXIT -> if (!touching && actions.isNotEmpty()) {
                handler.removeCallbacks(leftCheck)
                handler.postDelayed(leftCheck, PEN_LEFT_RANGE_MS)
            }
        }
    }

    /** Re-arms the idle check, e.g. once the view is attached again. */
    fun schedule() {
        handler.removeCallbacks(idleCheck)
        if (touching || actions.isEmpty()) return
        val wait = idleDelayMs - (SystemClock.uptimeMillis() - lastActivity)
        handler.postDelayed(idleCheck, wait.coerceAtLeast(0L))
    }

    /** Forgets a touch in progress, whose UP will never come (the view was detached). */
    fun cancelTouch() {
        touching = false
        handler.removeCallbacks(idleCheck)
        handler.removeCallbacks(leftCheck)
    }

    private fun isIdle() = !touching && SystemClock.uptimeMillis() - lastActivity >= idleDelayMs

    private fun runAll() {
        handler.removeCallbacks(idleCheck)
        handler.removeCallbacks(leftCheck)
        while (actions.isNotEmpty()) actions.removeFirst().invoke()
    }

    private companion object {
        /** Longer than the gap between hover ending and the tip touching down (a few ms on Musnap). */
        const val PEN_LEFT_RANGE_MS = 150L
    }
}
