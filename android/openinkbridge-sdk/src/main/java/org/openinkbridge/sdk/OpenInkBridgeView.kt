package org.openinkbridge.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView

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

    // Committed strokes rendered by the backend's own pen engine (see EpdAdapter.renderStroke), so
    // they look exactly like the low-latency preview. Null when the backend cannot render strokes.
    private var strokeLayer: Bitmap? = null
    private var isDrawing = false
    private var rawDrawingEnabled: Boolean? = null

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
     * Select the pen. Backends with their own pen engine use the matching vendor pen for both the
     * preview and the committed stroke.
     */
    fun setStylusTool(tool: StylusTool) {
        stylusTool = tool
        epdAdapterManager.activeAdapter.setStylusTool(tool)
    }

    fun addCompletedStroke(stroke: List<PenPoint>) {
        commitStroke(DrawingStroke(stroke, strokeColor, strokeWidth, stylusTool))
        onStrokeCompleted?.invoke(stroke)
    }

    private fun commitStroke(stroke: DrawingStroke) {
        completedStrokes.add(stroke)
        strokeLayer?.let { layer ->
            if (!epdAdapterManager.activeAdapter.renderStroke(Canvas(layer), stroke)) rebuildStrokeLayer()
        }
        invalidate()
    }

    private fun backendRendersStrokes(): Boolean =
        (epdAdapterManager.activeAdapter as? EpdAdapterIntrospection)
            ?.status()?.capabilities?.vendorStrokeRendering == true

    /** Re-renders every committed stroke through the backend, or drops the layer if it cannot. */
    private fun rebuildStrokeLayer() {
        strokeLayer?.recycle()
        strokeLayer = null
        if (width <= 0 || height <= 0 || !backendRendersStrokes()) return
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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

    fun setStylusOnly(enabled: Boolean) {
        this.stylusOnly = enabled
        epdAdapterManager.activeAdapter.setStylusOnly(enabled)
    }

    fun setRawDrawingEnabled(enabled: Boolean) {
        rawDrawingEnabled = enabled
        epdAdapterManager.activeAdapter.setRawDrawingEnabled(enabled)
    }

    /**
     * Whether the active backend needs raw drawing paused while touches land outside this view
     * (see [EpdAdapterCapabilities.rawDrawingTouchHandoff]). Hosts that toggle
     * [setRawDrawingEnabled] per ACTION_DOWN should only do so when this is true.
     */
    val needsRawDrawingTouchHandoff: Boolean
        get() = (epdAdapterManager.activeAdapter as? EpdAdapterIntrospection)
            ?.status()?.capabilities?.rawDrawingTouchHandoff == true

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

        val point = PenPoint(
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            tilt = event.getAxisValue(MotionEvent.AXIS_TILT),
            timestamp = event.eventTime
        )

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
                        val histPoint = PenPoint(
                            x = event.getHistoricalX(0, i),
                            y = event.getHistoricalY(0, i),
                            pressure = event.getHistoricalPressure(0, i),
                            tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, i),
                            timestamp = event.getHistoricalEventTime(i)
                        )
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
                    epdAdapterManager.activeAdapter.endStroke()

                    val smoothedStroke = CoreBridge.smoothStroke(strokePoints)
                    commitStroke(DrawingStroke(smoothedStroke, strokeColor, strokeWidth, stylusTool))
                    
                    onStrokeCompleted?.invoke(smoothedStroke)
                    strokePoints.clear()
                    isDrawing = false
                    
                    invalidate() // Trigger redraw to render the smoothed vector path
                }
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
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
        completedStrokes.clear()
        strokeLayer?.eraseColor(Color.TRANSPARENT)
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
            sb.append("\" stroke=\"$hexColor\" stroke-width=\"${stroke.width}\" fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
        }
        
        sb.append("</svg>")
        return sb.toString()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (epdAdapterManager.bind()) {
            epdAdapterManager.activeAdapter.setBrushStyle(strokeColor, strokeWidth)
            epdAdapterManager.activeAdapter.setStylusTool(stylusTool)
            epdAdapterManager.activeAdapter.setStylusOnly(stylusOnly)
            rawDrawingEnabled?.let(epdAdapterManager.activeAdapter::setRawDrawingEnabled)
        }
        // The bound backend decides whether committed strokes go through its pen engine.
        rebuildStrokeLayer()
    }

    override fun onDetachedFromWindow() {
        // Auto-release hardware E-Ink rendering locks to prevent screen flickering in other apps
        if (isDrawing) runCatching { epdAdapterManager.activeAdapter.endStroke() }
        strokePoints.clear()
        isDrawing = false
        epdAdapterManager.release()
        super.onDetachedFromWindow()
    }

    fun release() {
        epdAdapterManager.release()
    }
}
