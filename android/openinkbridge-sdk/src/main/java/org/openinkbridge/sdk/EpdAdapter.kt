package org.openinkbridge.sdk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/** Capabilities that are implemented by a backend, rather than inferred from the device brand. */
data class EpdAdapterCapabilities(
    val directDrawing: Boolean = false,
    val motionPrediction: Boolean = false,
    val refreshModeControl: Boolean = false,
    val fullRefresh: Boolean = false,
    val drawingLimit: Boolean = false,
    val rawDrawingToggle: Boolean = false,
    val hardwareScribbleHandoff: Boolean = false,
    /**
     * Raw drawing must be paused while touches land outside the drawing region, so hosts toggle
     * [EpdAdapter.setRawDrawingEnabled] on every ACTION_DOWN (Onyx). Backends whose vendor path
     * only inks inside its own region leave it false: toggling there costs the live ink of the
     * first stroke after any outside touch, since the vendor starts inking before the app sees it.
     */
    val rawDrawingTouchHandoff: Boolean = false,
    /**
     * [EpdAdapter.renderStroke] draws committed strokes with the same vendor pen engine that inks
     * the live preview, so a stroke looks identical before and after pen-up.
     */
    val vendorStrokeRendering: Boolean = false,
    /** [EpdAdapter.eraseOnPanel] shows an eraser on the panel at ink speed. */
    val panelErase: Boolean = false
)

data class EpdAdapterProbeResult(
    val supported: Boolean,
    val reason: String? = null
)

enum class EpdAdapterState {
    UNINITIALIZED,
    OPERATIONAL,
    DEGRADED,
    UNAVAILABLE,
    RELEASED
}

data class EpdAdapterStatus(
    val state: EpdAdapterState,
    val reason: String? = null,
    val capabilities: EpdAdapterCapabilities = EpdAdapterCapabilities()
) {
    val canRender: Boolean
        get() = state == EpdAdapterState.OPERATIONAL || state == EpdAdapterState.DEGRADED
}

interface EpdAdapter {
    /**
     * Bind the adapter to the rendering view.
     */
    fun init(view: View)

    /**
     * Render the active stroke path onto the main Canvas (for fallbacks/previews).
     */
    fun draw(canvas: Canvas) {}

    /**
     * Start a new low-latency stroke.
     */
    fun startStroke(tool: StylusTool, color: Int, width: Float)

    /**
     * Feed raw coordinates directly to the vendor's low-latency drawing path.
     */
    fun drawPoint(point: PenPoint)

    /**
     * Handle raw touch events for advanced adapters (e.g. predictive rendering).
     */
    fun onTouchEvent(event: MotionEvent) {}

    /**
     * End the current stroke and commit the vector data.
     */
    fun endStroke()

    /**
     * Clear the low-latency hardware canvas layer.
     */
    fun clear()

    /**
     * Manually trigger a full-screen refresh to clear E-Ink ghosting.
     */
    fun triggerFullRefresh()

    /**
     * Dynamically change the refresh mode of the screen.
     */
    fun setRefreshMode(mode: EInkRefreshMode)

    /**
     * Release any hardware resource hooks.
     */
    fun release()

    /**
     * Update active brush/stroke style dynamically.
     */
    fun setBrushStyle(color: Int, width: Float) {}

    /**
     * Checks if the adapter handles hardware-level direct drawing.
     */
    fun isDirectDrawingActive(): Boolean = false

    /**
     * Set stylus only mode to enable/disable finger touch in hardware level drawing.
     */
    fun setStylusOnly(enabled: Boolean) {}

    /**
     * Dynamically enable or disable E-Ink raw drawing.
     */
    fun setRawDrawingEnabled(enabled: Boolean) {}

    /**
     * Safely clear any persistent hardware-level scribbles from the screen,
     * typically called when the software canvas rendering has finished drawing.
     */
    fun clearHardwareScribble() {}

    /**
     * Handle hover events for low-latency active raw drawing toggling.
     */
    fun onHoverEvent(event: MotionEvent): Boolean = false

    /**
     * Set a boundary limit rectangle for low-latency drawing.
     */
    fun setDrawingLimit(rect: Rect?) {}

    /**
     * Select the pen used for the low-latency preview (and for [renderStroke], when supported).
     */
    fun setStylusTool(tool: StylusTool) {}

    /**
     * Erase a circle of [radiusPx] straight on the panel along the touch stream, at ink speed, while
     * the low-latency ink is off, painting [background] (host-sized, the content under the strokes)
     * or white. The host's own content is unchanged until it applies the erase. Feed it every event
     * of the erasing stroke, from its first, which may be mid-stroke, to its UP. Returns false when
     * the backend cannot.
     */
    fun eraseOnPanel(event: MotionEvent, radiusPx: Int, background: Bitmap? = null): Boolean = false

    /**
     * The points the current or last [eraseOnPanel] erase went through, in host coordinates: not
     * every point of the stroke, so an erase applied along these matches what the panel erased.
     */
    fun panelErasePath(): List<PointF> = emptyList()

    /**
     * Draw a committed stroke with the vendor's own pen engine into a software-backed [canvas]
     * (e.g. one wrapping a Bitmap). Returns false when the adapter cannot, in which case the host
     * renders the stroke itself.
     */
    fun renderStroke(canvas: Canvas, stroke: DrawingStroke): Boolean = false
}

/**
 * Optional runtime capability contract for adapters. Keeping this separate preserves binary
 * compatibility with adapters compiled against the original [EpdAdapter] interface.
 */
interface EpdAdapterIntrospection {
    fun probe(view: View): EpdAdapterProbeResult
    fun status(): EpdAdapterStatus
}

internal fun EpdAdapter.capabilities(): EpdAdapterCapabilities =
    (this as? EpdAdapterIntrospection)?.status()?.capabilities ?: EpdAdapterCapabilities()
