package org.openinkbridge.sdk

/**
 * Platform-independent pieces of the Musnap (iReader firmware) backend, kept free of Android and
 * vendor types so they can be unit-tested on the JVM.
 */

/** Pen callback actions reported by the firmware; they reuse `MotionEvent` action codes. */
internal object AhwAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3
}

/**
 * Accumulates the points the firmware reports while it inks a stroke, and hands back the finished
 * stroke on pen-up. Points arrive in overlay-local pixels; [offsetX]/[offsetY] translate them into
 * the host view's coordinate space.
 */
internal class AhwStrokeAssembler {
    private val points = mutableListOf<PenPoint>()

    var isStrokeActive: Boolean = false
        private set

    /** Returns the completed stroke when [action] ends it, null otherwise. */
    fun onPoint(
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        timestamp: Long,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        buttons: Int = PenButtons.NONE
    ): List<PenPoint>? {
        if (!x.isFinite() || !y.isFinite()) return null
        val point = PenPoint(
            x = x + offsetX,
            y = y + offsetY,
            pressure = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f,
            tilt = if (tilt.isFinite()) tilt else 0f,
            timestamp = timestamp,
            buttons = buttons
        )
        when (action) {
            AhwAction.DOWN -> {
                points.clear()
                points.add(point)
                isStrokeActive = true
            }
            AhwAction.MOVE -> {
                // A stroke that started before the listener was attached has no DOWN.
                if (!isStrokeActive) {
                    points.clear()
                    isStrokeActive = true
                }
                points.add(point)
            }
            AhwAction.UP -> {
                if (!isStrokeActive) return null
                points.add(point)
                return finish()
            }
            AhwAction.CANCEL -> reset()
        }
        return null
    }

    fun reset() {
        points.clear()
        isStrokeActive = false
    }

    private fun finish(): List<PenPoint> {
        val stroke = points.toList()
        reset()
        return stroke
    }
}

/**
 * Barrel-button state over time, taken from the `MotionEvent`s delivered alongside the firmware
 * pen. The firmware's own points carry no buttons but share the events' `eventTime`, and they
 * arrive later than the events, so each point is matched to the state at its timestamp.
 */
internal class StylusButtonTimeline(private val capacity: Int = 64) {
    private val times = LongArray(capacity)
    private val states = IntArray(capacity)
    private var size = 0
    private var start = 0

    @Synchronized
    fun record(eventTime: Long, buttons: Int) {
        if (size > 0) {
            val last = (start + size - 1) % capacity
            if (states[last] == buttons) return
            if (eventTime < times[last]) return
        }
        val slot = (start + size) % capacity
        times[slot] = eventTime
        states[slot] = buttons
        if (size < capacity) size++ else start = (start + 1) % capacity
    }

    /** The buttons held at [eventTime]: the last change at or before it. */
    @Synchronized
    fun buttonsAt(eventTime: Long): Int {
        for (i in size - 1 downTo 0) {
            val slot = (start + i) % capacity
            if (times[slot] <= eventTime) return states[slot]
        }
        return PenButtons.NONE
    }

    @Synchronized
    fun reset() {
        size = 0
        start = 0
    }
}

internal object MusnapPenMapping {
    /**
     * The firmware pen takes its width in physical pixels (verified on a Musnap Ocean by matching a
     * 4px host stroke) and draws it constant. `OpenInkBridgeView` renders a committed stroke at
     * `width × density × pressure`, so the preview assumes [TYPICAL_PRESSURE] — what this pen
     * reports for ordinary writing — or the ink visibly thins when the host takes over.
     */
    fun previewWidthPx(logicalWidth: Float, density: Float, pressure: Float = TYPICAL_PRESSURE): Float {
        val width = if (logicalWidth.isFinite()) logicalWidth else 1f
        val scale = if (density.isFinite() && density > 0f) density else 1f
        val weight = if (pressure.isFinite() && pressure > 0f) pressure else 1f
        return (width * scale * weight).coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
    }

    const val TYPICAL_PRESSURE = 0.5f

    /**
     * The fast path inks a monochrome panel with a fast waveform and only defines black, grey and
     * white pens (`HandPenConfig.PEN_COLOR_*`): a mid-tone colour such as a web page's green comes
     * out invisible. The preview therefore takes the nearest of the three by luminance; the
     * committed stroke keeps its real colour.
     */
    fun previewColor(argb: Int): Int {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return when {
            luminance < DARK_LUMINANCE -> PEN_BLACK
            luminance < LIGHT_LUMINANCE -> PEN_GRAY
            else -> PEN_WHITE
        }
    }

    /** Name of the `android.eink.view.HandPenConfig` pen used for each tool. */
    fun penTypeConstant(tool: StylusTool): String = when (tool) {
        StylusTool.PEN -> "PEN_TYPE_GANGBI" // fountain pen, pressure-sensitive
        StylusTool.PENCIL -> "PEN_TYPE_QIANBI"
        StylusTool.BRUSH -> "PEN_TYPE_MAOBI"
        StylusTool.HIGHLIGHTER, StylusTool.ERASER_STROKE, StylusTool.ERASER_POINT -> "PEN_TYPE_SIMPLE"
    }

    // Values of android.eink.view.HandPenConfig.PEN_COLOR_* on device.
    const val PEN_BLACK = 0xFF000000.toInt()
    const val PEN_GRAY = 0xFF505050.toInt()
    const val PEN_WHITE = 0xFFFFFFFF.toInt()
    private const val DARK_LUMINANCE = 0.45f
    private const val LIGHT_LUMINANCE = 0.9f

    /**
     * Names of the firmware's `android.eink.api.EinkMode` constants for each OIP refresh mode,
     * resolved by name at runtime so values always come from the device. `UI_DEFAULT_MODE` is
     * already a partial GC16 update on this firmware (both are 2), so QUALITY takes the full one.
     */
    fun updateModeConstant(mode: EInkRefreshMode): String = when (mode) {
        EInkRefreshMode.SPEED -> "UI_EINK_DU_PART_MODE"
        EInkRefreshMode.BALANCED -> "UI_DEFAULT_MODE"
        EInkRefreshMode.QUALITY -> "UI_EINK_GC16_FULL_MODE"
    }

    private const val MIN_WIDTH_PX = 0.5f
    private const val MAX_WIDTH_PX = 128f
}
