package org.openinkbridge.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A zero-configuration standalone drawing canvas view for native Android.
 * Automatically handles stylus coordinate capturing, high-frequency motion history,
 * stroke smoothing, and hardware E-Ink rendering updates based on device vendor.
 */
@SuppressLint("ClickableViewAccessibility")
class OpenInkBridgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private val epdAdapterManager = EpdAdapterManager(this)
    private var onStrokeCompleted: ((List<PenPoint>) -> Unit)? = null



    private val strokePoints = mutableListOf<PenPoint>()
    private val completedStrokes = mutableListOf<DrawingStroke>()
    
    private var strokeColor = Color.BLACK
    private var strokeWidth = 5f
    private var stylusTool = StylusTool.PEN
    // The last drawing tool selected: what strokes are committed with while an eraser is selected.
    private var penTool = StylusTool.PEN

    // Committed strokes rendered by the backend's own pen engine (see EpdAdapter.renderStroke), so
    // they look exactly like the low-latency preview. Null when the backend cannot render strokes.
    private var strokeLayer: Bitmap? = null
    private var isDrawing = false
    private var rawDrawingEnabled: Boolean? = null

    private var onStylusButtonsChanged: ((Int) -> Unit)? = null
    private var stylusButtons = PenButtons.NONE
    private var inkingPaused = false
    // Whether the backend's low-latency ink is off, for a pause or an eraser.
    private var inkSuspended = false
    // Latched at ACTION_DOWN: a stroke keeps the routing it started with.
    private var strokePaused = false

    private var erase: EraseSession? = null
    private var onStrokesErased: ((StrokeErase) -> Unit)? = null
    private var eraserBackground: ((Canvas) -> Unit)? = null
    private var eraserBackgroundBitmap: Bitmap? = null
    private val eraserClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** Radius of the eraser of [StylusTool.ERASER_POINT] and [StylusTool.ERASER_STROKE], in pixels. */
    var eraserRadiusPx: Float = 6f * resources.displayMetrics.density

    // Standard drawing paint for rendering historical strokes on screen
    private val renderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    init {
        // Enable onDraw on SurfaceView so standard vector path drawing works
        setWillNotDraw(false)
        
        // Read XML attributes if available
        attrs?.let {
            // Placeholder: Parse strokeColor/strokeWidth attributes from XML custom attributes
        }
    }

    /**
     * Configure the drawing brush settings.
     */
    fun setBrushStyle(color: Int, width: Float) {
        this.strokeColor = color
        this.strokeWidth = width
        epdAdapterManager.activeAdapter.setBrushStyle(color, width)
        invalidate()
    }

    /**
     * Set a listener to receive finalized vector path data when the pen is lifted.
     */
    fun setOnStrokeListener(listener: (List<PenPoint>) -> Unit) {
        this.onStrokeCompleted = listener
    }

    /**
     * Called with the [PenButtons] bitmask whenever the stylus barrel buttons change, as soon as
     * the platform reports it (on some pens only while the tip touches the screen). Calling
     * [setInkingPaused] or [setStylusTool] from here takes effect for the stroke that reported the
     * change.
     */
    fun setOnStylusButtonListener(listener: ((buttons: Int) -> Unit)?) {
        onStylusButtonsChanged = listener
    }

    private val penIdle = PenIdleTracker()

    /**
     * How long after the last stroke the pen counts as idle for [runWhenPenIdle], in milliseconds.
     * A pen leaving hover range counts as idle at once.
     */
    var penIdleDelayMs: Long
        get() = penIdle.idleDelayMs
        set(value) {
            penIdle.idleDelayMs = value
        }

    /**
     * Run [action] once the pen is idle: no stroke for [penIdleDelayMs], or the pen out of range.
     * Runs it now if the pen is already idle. Use it for view updates that can wait (marks,
     * highlights, status text): on some e-ink backends (Musnap) any view update made while the
     * user writes delays the ink of the next stroke by a couple of hundred milliseconds.
     * Actions run on the main thread, in the order they were queued.
     */
    fun runWhenPenIdle(action: () -> Unit) = penIdle.runWhenIdle(action)

    /** Tracks pen-down, pen-up and the pen leaving range, from whichever path sees the stylus. */
    internal fun reportPenActivity(event: MotionEvent) = penIdle.onPenEvent(event)

    /** The barrel buttons last reported by the stylus, as a [PenButtons] bitmask. */
    val currentStylusButtons: Int
        get() = stylusButtons

    internal fun reportStylusButtons(buttons: Int) {
        if (buttons == stylusButtons) return
        stylusButtons = buttons
        onStylusButtonsChanged?.invoke(buttons)
    }

    private fun reportStylusButtons(event: MotionEvent) {
        val tool = event.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            reportStylusButtons(PenButtons.fromAndroidButtonState(event.buttonState))
        }
    }

    /**
     * Pause the backend's low-latency ink, e.g. while a barrel button selects or erases. Strokes
     * that start while paused are reported to the stroke listener but neither inked nor added to
     * the canvas; whatever the vendor path already inked is cleared. A stroke keeps the mode it
     * started in.
     */
    fun setInkingPaused(paused: Boolean) {
        if (inkingPaused == paused) return
        inkingPaused = paused
        updateInking()
    }

    private fun updateInking() {
        val suspend = inkingPaused || stylusTool.isEraser || erase != null
        if (suspend == inkSuspended) return
        inkSuspended = suspend
        val adapter = epdAdapterManager.activeAdapter
        // The vendor ink is about to be cleared: draw the strokes it still stands in for.
        if (suspend) renderInkedStrokes()
        adapter.setRawDrawingEnabled(!suspend && rawDrawingEnabled != false)
        if (suspend) adapter.clearHardwareScribble()
    }

    val isInkingPaused: Boolean
        get() = inkingPaused

    /**
     * Select the pen. Backends with their own pen engine use the matching vendor pen for both the
     * preview and the committed stroke. With [StylusTool.ERASER_POINT] the stylus cuts what it
     * crosses out of [strokes], and with [StylusTool.ERASER_STROKE] (also the stylus's eraser end)
     * removes whole strokes; see [setOnStrokesErasedListener]. Selecting an eraser takes effect at
     * once, even mid-stroke, e.g. from [setOnStylusButtonListener]; leaving it takes effect once
     * the erasing stroke ends.
     */
    fun setStylusTool(tool: StylusTool) {
        stylusTool = tool
        if (!tool.isEraser) {
            penTool = tool
            epdAdapterManager.activeAdapter.setStylusTool(tool)
        }
        updateInking()
    }

    /** Called once an erasing stroke ends, with what it changed in [strokes]; not when it changed nothing. */
    fun setOnStrokesErasedListener(listener: ((StrokeErase) -> Unit)?) {
        onStrokesErased = listener
    }

    /**
     * Draw what the eraser uncovers, whatever lies under the strokes (a grid, a page), into a
     * canvas the size of this view, already filled white. Called when an erase starts, on backends
     * that erase straight on the panel at ink speed (Musnap); without it the panel shows white
     * there until the view redraws.
     */
    fun setEraserBackground(draw: ((Canvas) -> Unit)?) {
        eraserBackground = draw
        if (draw == null) {
            eraserBackgroundBitmap?.recycle()
            eraserBackgroundBitmap = null
        }
    }

    /**
     * Commit a stroke as if it had just been drawn: with the given pen (by default the current
     * one), reported to the stroke listener unless [notify] is false. Parts crossing
     * [setExcludedRegions] are cut out: each remaining piece is committed and reported on its own.
     * To put back many strokes at once, e.g. after removing some, assign [strokes].
     */
    @JvmOverloads
    fun addCompletedStroke(
        stroke: List<PenPoint>,
        tool: StylusTool = penTool,
        color: Int = strokeColor,
        width: Float = strokeWidth,
        notify: Boolean = true
    ) {
        for (piece in outsideExcludedRegions(stroke)) {
            commitStroke(DrawingStroke(piece, color, width, tool))
            if (notify) onStrokeCompleted?.invoke(piece)
        }
    }

    /**
     * The committed strokes, oldest first. Assigning replaces them all, e.g. to restore a saved
     * drawing or remove some strokes: each is drawn with its own pen, colour and width (through
     * the backend's pen engine when it has one), and the stroke listener is not called. Assigning
     * an empty list is [clear].
     */
    var strokes: List<DrawingStroke>
        get() = completedStrokes.toList()
        set(value) {
            // Strokes still shown only as vendor ink are not in the layer yet: count them as new.
            val drawn = completedStrokes.filterNot(unrenderedStrokes.toHashSet()::contains)
            unrenderedStrokes.clear()
            val dirty = changedArea(drawn, value)
            completedStrokes.clear()
            completedStrokes.addAll(value)
            epdAdapterManager.activeAdapter.clearHardwareScribble()
            if (dirty == null || !redrawStrokeLayer(dirty)) rebuildStrokeLayer()
            // Only the strokes that came or went need repainting; on e-ink a smaller update is a
            // faster one.
            if (dirty != null) invalidate(dirty)
        }

    /**
     * Replaces [strokes] with [after], cut along [path], by clearing the eraser's circle along it
     * from the stroke layer instead of re-rendering the strokes that changed: re-rendered, cut
     * strokes change shape slightly from what the panel erased.
     */
    private fun clearErasedPath(after: List<DrawingStroke>, path: List<FloatArray>) {
        val layer = strokeLayer
        if (layer == null || layer.width != width || layer.height != height) {
            strokes = after
            return
        }
        renderInkedStrokes()
        completedStrokes.clear()
        completedStrokes.addAll(after)
        val trace = Path().apply {
            moveTo(path[0][0], path[0][1])
            // A lone point still erases its circle: a zero-length line with round caps.
            if (path.size == 1) lineTo(path[0][0], path[0][1])
            for (i in 1 until path.size) lineTo(path[i][0], path[i][1])
        }
        eraserClearPaint.strokeWidth = eraserRadiusPx * 2
        Canvas(layer).drawPath(trace, eraserClearPaint)
        val bounds = RectF().also { trace.computeBounds(it, true) }
        bounds.inset(-eraserRadiusPx - 2f, -eraserRadiusPx - 2f)
        epdAdapterManager.activeAdapter.clearHardwareScribble()
        invalidate(Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt() + 1, bounds.bottom.toInt() + 1))
    }

    private fun changedArea(before: List<DrawingStroke>, after: List<DrawingStroke>): Rect? {
        val kept = after.toHashSet()
        val previous = before.toHashSet()
        val area = RectF()
        val pad = resources.displayMetrics.density
        for (stroke in before.filterNot(kept::contains) + after.filterNot(previous::contains)) {
            if (stroke.points.isEmpty()) continue
            val margin = stroke.width * pad * 2f + 2f
            for (p in stroke.points) area.union(p.x - margin, p.y - margin, p.x + margin, p.y + margin)
        }
        if (area.isEmpty) return null
        return Rect(area.left.toInt() - 1, area.top.toInt() - 1, area.right.toInt() + 2, area.bottom.toInt() + 2)
    }

    private val unrenderedStrokes = mutableListOf<DrawingStroke>()

    /**
     * A stroke the backend inked itself: recorded and reported now, but drawn into the view only
     * once the pen is idle, its vendor ink standing in until then. Redrawing the view while the
     * user writes delays the next stroke's ink on some backends (Musnap).
     */
    internal fun commitInkedStroke(points: List<PenPoint>) {
        val stroke = DrawingStroke(points, strokeColor, strokeWidth, penTool)
        completedStrokes.add(stroke)
        unrenderedStrokes.add(stroke)
        onStrokeCompleted?.invoke(points)
        if (unrenderedStrokes.size == 1) runWhenPenIdle(::renderInkedStrokes)
    }

    private fun renderInkedStrokes() {
        if (unrenderedStrokes.isEmpty()) return
        val area = changedArea(emptyList(), unrenderedStrokes)
        strokeLayer?.let { layer ->
            val canvas = Canvas(layer)
            if (!unrenderedStrokes.all { epdAdapterManager.activeAdapter.renderStroke(canvas, it) }) rebuildStrokeLayer()
        }
        unrenderedStrokes.clear()
        if (area != null) invalidate(area) else invalidate()
        epdAdapterManager.activeAdapter.clearHardwareScribble()
    }

    private fun commitStroke(stroke: DrawingStroke) {
        completedStrokes.add(stroke)
        strokeLayer?.let { layer ->
            if (!epdAdapterManager.activeAdapter.renderStroke(Canvas(layer), stroke)) rebuildStrokeLayer()
        }
        invalidate()
    }

    private fun backendRendersStrokes(): Boolean = epdAdapterManager.activeAdapter.capabilities().vendorStrokeRendering

    /**
     * Re-renders only [area] of the stroke layer, from the strokes that reach into it. Returns
     * false when there is no layer to update in place, or the backend fails to render a stroke.
     */
    private fun redrawStrokeLayer(area: Rect): Boolean {
        val layer = strokeLayer ?: return false
        if (layer.width != width || layer.height != height) return false
        val canvas = Canvas(layer)
        if (!canvas.clipRect(area)) return true
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val density = resources.displayMetrics.density
        val bounds = RectF()
        for (stroke in completedStrokes) {
            if (stroke.points.isEmpty()) continue
            val margin = stroke.width * density * 2f + 2f
            bounds.set(stroke.points[0].x, stroke.points[0].y, stroke.points[0].x, stroke.points[0].y)
            for (p in stroke.points) bounds.union(p.x, p.y)
            bounds.inset(-margin, -margin)
            if (!RectF.intersects(bounds, RectF(area))) continue
            if (!epdAdapterManager.activeAdapter.renderStroke(canvas, stroke)) return false
        }
        return true
    }

    /** Re-renders every committed stroke through the backend, or drops the layer if it cannot. */
    private fun rebuildStrokeLayer() {
        val previous = strokeLayer
        strokeLayer = null
        if (width <= 0 || height <= 0 || !backendRendersStrokes()) {
            previous?.recycle()
            return
        }
        // Reuse the layer when the size is unchanged: a full-screen bitmap per rebuild adds up.
        val layer = if (previous != null && previous.width == width && previous.height == height) {
            previous.apply { eraseColor(Color.TRANSPARENT) }
        } else {
            previous?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(layer)
        if (completedStrokes.all { epdAdapterManager.activeAdapter.renderStroke(canvas, it) }) {
            strokeLayer = layer
        } else {
            layer.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildStrokeLayer()
    }

    private var stylusOnly = true
    private var excludedRegions: List<Rect> = emptyList()
    private var excludedBoxes: List<ExcludedBox> = emptyList()

    /**
     * Areas of this view, in its px, that take taps rather than writing (e.g. cells); empty for
     * none. Backends that support it keep their low-latency ink out of them, and the pen still
     * reaches the view there as touch events. On every backend, strokes committed from then on
     * are cut where they cross a region: only the pieces outside are kept and reported. Kept and
     * re-applied whenever the backend binds again.
     */
    fun setExcludedRegions(regions: List<RectF>) {
        excludedRegions = regions.map { r -> Rect().also(r::roundOut) }
        excludedBoxes = excludedRegions.map { ExcludedBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        epdAdapterManager.activeAdapter.setExcludedRegions(excludedRegions)
    }

    private fun outsideExcludedRegions(stroke: List<PenPoint>): List<List<PenPoint>> =
        ExcludedRegions.split(stroke, excludedBoxes)

    fun setStylusOnly(enabled: Boolean) {
        this.stylusOnly = enabled
        epdAdapterManager.activeAdapter.setStylusOnly(enabled)
    }

    fun setRawDrawingEnabled(enabled: Boolean) {
        rawDrawingEnabled = enabled
        epdAdapterManager.activeAdapter.setRawDrawingEnabled(enabled && !inkSuspended)
    }

    /**
     * Whether the active backend needs raw drawing paused while touches land outside this view
     * (see [EpdAdapterCapabilities.rawDrawingTouchHandoff]). Hosts that toggle
     * [setRawDrawingEnabled] per ACTION_DOWN should only do so when this is true.
     */
    val needsRawDrawingTouchHandoff: Boolean
        get() = epdAdapterManager.activeAdapter.capabilities().rawDrawingTouchHandoff

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE) {
            if (OpenInkBridgeLogger.shouldLogTrace()) {
                OpenInkBridgeLogger.t(Subsystem.Android, "OpenInkBridgeView", "DISPATCH_TOUCH", "x=${event.x} y=${event.y}")
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (OpenInkBridgeLogger.shouldLogTrace()) {
            OpenInkBridgeLogger.t(Subsystem.PenInput, "OpenInkBridgeView", "ON_TOUCH_EVENT", "action=${event.action} tool=$tool isStylus=$isStylus stylusOnly=$stylusOnly directDraw=${epdAdapterManager.activeAdapter.isDirectDrawingActive()}")
        }
        if (stylusOnly && !isStylus) {
            return false
        }
        reportStylusButtons(event)
        reportPenActivity(event)
        if (erase?.finished == false || stylusTool.isEraser || tool == MotionEvent.TOOL_TYPE_ERASER) return eraseAlong(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) strokePaused = inkingPaused
        if (strokePaused) return capturePausedStroke(event)

        // When TouchHelper is active AND input is a stylus, TouchHelper handles hardware direct drawing callbacks.
        // For finger touches (!isStylus), process via standard software stroke rendering below.
        if (isStylus && epdAdapterManager.activeAdapter.isDirectDrawingActive()) {
            OpenInkBridgeLogger.d(Subsystem.Android, "OpenInkBridgeView", "TOUCH_HELPER_ROUTE", "Routing to super.onTouchEvent for TouchHelper (Stylus)")
            epdAdapterManager.activeAdapter.onTouchEvent(event)
            super.onTouchEvent(event)
            return true // Consumes the touch stream to receive move/up actions
        }

        
        epdAdapterManager.activeAdapter.onTouchEvent(event)
        val toolType = when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> StylusTool.PEN
            MotionEvent.TOOL_TYPE_ERASER -> StylusTool.ERASER_STROKE
            else -> StylusTool.PEN
        }

        val point = currentPoint(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                strokePoints.clear()
                strokePoints.add(point)
                
                epdAdapterManager.activeAdapter.startStroke(toolType, strokeColor, strokeWidth)
                epdAdapterManager.activeAdapter.drawPoint(point)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    val historySize = event.historySize
                    for (i in 0 until historySize) {
                        val histPoint = historicalPoint(event, i)
                        strokePoints.add(histPoint)
                        epdAdapterManager.activeAdapter.drawPoint(histPoint)
                    }

                    strokePoints.add(point)
                    epdAdapterManager.activeAdapter.drawPoint(point)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    strokePoints.add(point)
                    epdAdapterManager.activeAdapter.drawPoint(point)
                    finishStroke()
                }
            }
        }
        return true
    }

    private fun finishStroke() {
        epdAdapterManager.activeAdapter.endStroke()
        val smoothedStroke = CoreBridge.smoothStroke(strokePoints)
        for (piece in outsideExcludedRegions(smoothedStroke)) {
            commitStroke(DrawingStroke(piece, strokeColor, strokeWidth, penTool))
            onStrokeCompleted?.invoke(piece)
        }
        strokePoints.clear()
        isDrawing = false
        invalidate() // Trigger redraw to render the smoothed vector path
    }

    private fun currentPoint(event: MotionEvent) = PenPoint(
        x = event.x,
        y = event.y,
        pressure = event.pressure,
        tilt = event.getAxisValue(MotionEvent.AXIS_TILT),
        timestamp = event.eventTime,
        buttons = PenButtons.fromAndroidButtonState(event.buttonState)
    )

    private fun historicalPoint(event: MotionEvent, index: Int) = PenPoint(
        x = event.getHistoricalX(0, index),
        y = event.getHistoricalY(0, index),
        pressure = event.getHistoricalPressure(0, index),
        tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, index),
        timestamp = event.getHistoricalEventTime(index),
        buttons = PenButtons.fromAndroidButtonState(event.buttonState)
    )

    /** A stroke drawn while inking is paused: collected and reported, never inked or committed. */
    private fun capturePausedStroke(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                strokePoints.clear()
                strokePoints.add(currentPoint(event))
            }
            MotionEvent.ACTION_MOVE -> if (isDrawing) {
                for (i in 0 until event.historySize) strokePoints.add(historicalPoint(event, i))
                strokePoints.add(currentPoint(event))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isDrawing) {
                strokePoints.add(currentPoint(event))
                finishPausedStroke()
            }
        }
        return true
    }

    private fun finishPausedStroke() {
        val stroke = CoreBridge.smoothStroke(strokePoints.toList())
        strokePoints.clear()
        isDrawing = false
        strokePaused = false
        onStrokeCompleted?.invoke(stroke)
    }

    /** An erasing stroke in progress, from its first event to its UP and the erase being applied. */
    private class EraseSession(val before: List<DrawingStroke>, val wholeStrokes: Boolean, var onPanel: Boolean) {
        var panelUsed = false
        var finished = false
        var last: FloatArray? = null
    }

    /**
     * Erases along the stylus. Where the backend erases on the panel, it shows the erase at ink speed
     * and the strokes are cut once, at pen-up, along the path it erased: the view then clears that
     * path from what it shows rather than re-rendering the cut strokes, so the result matches the
     * panel exactly. Elsewhere the strokes are cut and redrawn as the stylus moves.
     */
    private fun eraseAlong(event: MotionEvent): Boolean {
        val adapter = epdAdapterManager.activeAdapter
        val end = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val session = erase?.takeUnless { it.finished } ?: run {
            if (end) return true
            // The stroke the eraser interrupts keeps what was drawn of it.
            if (isDrawing) if (strokePaused) finishPausedStroke() else finishStroke()
            val wholeStrokes = stylusTool == StylusTool.ERASER_STROKE ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
            EraseSession(completedStrokes.toList(), wholeStrokes, onPanel = adapter.capabilities().panelErase)
                .also { erase = it; updateInking() }
        }
        if (session.onPanel) {
            val background = if (session.panelUsed) eraserBackgroundBitmap else drawEraserBackground()
            session.onPanel = adapter.eraseOnPanel(event, eraserRadiusPx.roundToInt(), background)
            session.panelUsed = session.panelUsed || session.onPanel
        }
        if (!session.onPanel) eraseInView(session, event)
        if (end) finishErase(session, event)
        return true
    }

    private fun eraseInView(session: EraseSession, event: MotionEvent) {
        val path = ArrayList<FloatArray>(event.historySize + 2)
        session.last?.let(path::add)
        for (i in 0 until event.historySize) path += floatArrayOf(event.getHistoricalX(i), event.getHistoricalY(i))
        path += floatArrayOf(event.x, event.y)
        session.last = path.last()
        val after = StrokeEraser.erase(completedStrokes, path, eraserRadiusPx, resources.displayMetrics.density, session.wholeStrokes)
        if (after != null) strokes = after
    }

    private fun finishErase(session: EraseSession, event: MotionEvent) {
        session.finished = true
        if (session.panelUsed) {
            val path = epdAdapterManager.activeAdapter.panelErasePath().map { floatArrayOf(it.x, it.y) }
                .ifEmpty { listOf(floatArrayOf(event.x, event.y)) }
            val after = StrokeEraser.erase(completedStrokes, path, eraserRadiusPx, resources.displayMetrics.density, session.wholeStrokes)
            when {
                after == null -> {}
                // Whole strokes go: redrawing what is left there is what the panel needs to show.
                session.wholeStrokes -> strokes = after
                else -> clearErasedPath(after, path)
            }
        }
        val after = completedStrokes.toList()
        val kept = after.toHashSet()
        val previous = session.before.toHashSet()
        val change = StrokeErase(session.before.filterNot(kept::contains), after.filterNot(previous::contains))
        if (change.removed.isNotEmpty() || change.added.isNotEmpty()) onStrokesErased?.invoke(change)
        // The ink resumes only once the view shows the applied erase: resuming earlier repaints the
        // panel from the view as it was, flashing the erased ink back.
        Choreographer.getInstance().postFrameCallback {
            if (erase === session) {
                erase = null
                updateInking()
            }
        }
    }

    private fun drawEraserBackground(): Bitmap? {
        val draw = eraserBackground ?: return null
        if (width <= 0 || height <= 0) return null
        val bitmap = eraserBackgroundBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                eraserBackgroundBitmap?.recycle()
                eraserBackgroundBitmap = it
            }
        bitmap.eraseColor(Color.WHITE)
        draw(Canvas(bitmap))
        return bitmap
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        reportStylusButtons(event)
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        reportStylusButtons(event)
        reportPenActivity(event)
        if (epdAdapterManager.activeAdapter.isDirectDrawingActive()) {
            if (epdAdapterManager.activeAdapter.onHoverEvent(event)) {
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        // Clear canvas with white background
        canvas.drawColor(Color.WHITE)

        val density = resources.displayMetrics.density

        val layer = strokeLayer
        if (layer != null) {
            canvas.drawBitmap(layer, 0f, 0f, null)
        } else for (stroke in completedStrokes) {
            if (stroke.points.size < 2) continue
            renderPaint.color = stroke.color
            for (i in 0 until stroke.points.size - 1) {
                val p1 = stroke.points[i]
                val p2 = stroke.points[i + 1]
                val avgPressure = (p1.pressure + p2.pressure) / 2f
                val width = (stroke.width * density * avgPressure).coerceAtLeast(0.5f)
                renderPaint.strokeWidth = width
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, renderPaint)
            }
        }

        // Draw the currently active stroke preview (e.g. standard software or predictive)
        epdAdapterManager.activeAdapter.draw(canvas)
    }

    /**
     * Clear the canvas and completed vector paths.
     */
    fun clear() {
        strokes = emptyList()
        epdAdapterManager.activeAdapter.clear()
        invalidate()
    }

    /**
     * Export drawing as an Android Bitmap object.
     */
    fun exportToBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        return bitmap
    }

    /**
     * Export drawing direct to an SVG string.
     */
    fun exportToSvg(): String {
        val sb = StringBuilder()
        sb.append("<svg viewBox=\"0 0 $width $height\" xmlns=\"http://www.w3.org/2000/svg\">\n")
        
        for (stroke in completedStrokes) {
            if (stroke.points.size < 2) continue
            val hexColor = String.format("#%06X", 0xFFFFFF and stroke.color)
            sb.append("  <path d=\"M ${stroke.points[0].x} ${stroke.points[0].y}")
            for (i in 1 until stroke.points.size) {
                sb.append(" L ${stroke.points[i].x} ${stroke.points[i].y}")
            }
            sb.append("\" stroke=\"$hexColor\" stroke-width=\"${stroke.width}\" data-tool=\"${stroke.tool.name.lowercase()}\" fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
        }
        
        sb.append("</svg>")
        return sb.toString()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (epdAdapterManager.bind()) {
            epdAdapterManager.activeAdapter.setBrushStyle(strokeColor, strokeWidth)
            epdAdapterManager.activeAdapter.setStylusTool(penTool)
            epdAdapterManager.activeAdapter.setStylusOnly(stylusOnly)
            if (excludedRegions.isNotEmpty()) epdAdapterManager.activeAdapter.setExcludedRegions(excludedRegions)
            if (inkSuspended) {
                epdAdapterManager.activeAdapter.setRawDrawingEnabled(false)
            } else {
                rawDrawingEnabled?.let(epdAdapterManager.activeAdapter::setRawDrawingEnabled)
            }
        }
        // The bound backend decides whether committed strokes go through its pen engine.
        rebuildStrokeLayer()
        penIdle.schedule()
    }

    override fun onDetachedFromWindow() {
        // Auto-release hardware E-Ink rendering locks to prevent screen flickering in other apps
        if (isDrawing) runCatching { epdAdapterManager.activeAdapter.endStroke() }
        strokePoints.clear()
        isDrawing = false
        strokePaused = false
        erase = null
        inkSuspended = inkingPaused || stylusTool.isEraser
        penIdle.cancelTouch()
        epdAdapterManager.release()
        super.onDetachedFromWindow()
    }

    /** Full-panel refresh through the active backend, to clear e-ink ghosting. */
    fun triggerFullRefresh() {
        epdAdapterManager.activeAdapter.triggerFullRefresh()
    }

    fun release() {
        epdAdapterManager.release()
    }
}
