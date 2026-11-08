package org.openinkbridge.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Musnap e-ink tablets (iReader / ZhangYue firmware, e.g. Musnap Ocean).
 *
 * The firmware exposes a system pen fast path ("auto handwriting") through the `android.eink`
 * package of its framework: strokes are inked straight to the EPDC while the pen moves, and the
 * points are reported back to the app. The path only exists on the firmware's own
 * `HWSurfaceView` — binding it to an arbitrary SurfaceView needs an API the firmware blocks for
 * third-party apps — so this adapter places a transparent `HWSurfaceView` over the host's drawing
 * region (the hybrid overlay of OIP §1.3), hands each finished stroke to the host, and clears the
 * overlay once the host has committed a frame containing it.
 *
 * Everything is resolved by reflection, so the SDK has no compile-time dependency and the probe
 * fails cleanly on any other device. Only APIs verified to be callable from a non-privileged app
 * are used.
 */
@SuppressLint("ClickableViewAccessibility")
class MusnapEpdAdapter : EpdAdapter, EpdAdapterIntrospection {
    private var api: MusnapEinkApi? = null
    private var hostView: View? = null
    private var overlay: SurfaceView? = null
    private var overlayParent: ViewGroup? = null
    private var fbSurface: Any? = null

    private var adapterState = EpdAdapterState.UNINITIALIZED
    private var statusReason: String? = null

    private val assembler = AhwStrokeAssembler()
    private val buttonTimeline = StylusButtonTimeline()
    private var strokeColor = Color.BLACK
    private var strokeWidth = 5f
    private var rawDrawingEnabled = true
    private var windowFocused = true
    private var hostShown = true
    private var autoHandWriteApplied: Boolean? = null
    // Uptime at which inking was last turned off, while it stays off. Firmware points lag the
    // input, so points stamped before it still finish the stroke the firmware was inking.
    @Volatile private var inkingOffSince: Long? = null
    private var drawingLimit: Rect? = null
    private val region = Rect()
    private var clearScheduled = false
    private var recreatePending = false

    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null

    // Software preview for input the firmware does not ink (fingers when stylusOnly is off).
    private val previewPoints = mutableListOf<PenPoint>()
    private val previewPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private var stylusTool = StylusTool.PEN

    private fun capabilities() = EpdAdapterCapabilities(
        directDrawing = true,
        refreshModeControl = true,
        fullRefresh = true,
        drawingLimit = true,
        rawDrawingToggle = true,
        hardwareScribbleHandoff = true,
        vendorStrokeRendering = api?.canRenderStrokes == true
    )

    /**
     * Hosts that commit through [renderStroke] get the tool's own firmware pen in the preview too,
     * so both are the same engine. Others (WebView pages) render strokes themselves, so the preview
     * is a plain constant-width pen sized to look like their result.
     */
    private fun hostRendersWithFirmwarePen(): Boolean =
        api?.canRenderStrokes == true && hostView is OpenInkBridgeView

    override fun probe(view: View): EpdAdapterProbeResult {
        return try {
            api = MusnapEinkApi.resolve()
            EpdAdapterProbeResult(supported = true)
        } catch (error: Throwable) {
            EpdAdapterProbeResult(
                supported = false,
                reason = "Musnap android.eink pen API unavailable: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    override fun init(view: View) {
        val resolved = api ?: MusnapEinkApi.resolve().also { api = it }
        hostView = view
        adapterState = EpdAdapterState.DEGRADED
        statusReason = "Waiting for the pen overlay surface"
        view.post { attachOverlay(view, resolved) }
        OpenInkBridgeLogger.i(Subsystem.Backend, TAG, "INIT", "Musnap pen API resolved (listener V2=${resolved.hasListenerV2})")
        warnIfEdgeGesturesStayArmed(view)
    }

    /**
     * The firmware's edge-swipe gestures (back, menus) also fire for the stylus, so a stroke
     * starting near a screen edge can close the app. They are disarmed for the stylus only in
     * activities whose class name contains one of [HANDWRITING_ACTIVITY_MARKERS].
     */
    private fun warnIfEdgeGesturesStayArmed(view: View) {
        val activityName = findActivity(view.context)?.javaClass?.name ?: return
        if (HANDWRITING_ACTIVITY_MARKERS.none { activityName.contains(it) }) {
            OpenInkBridgeLogger.w(
                Subsystem.Backend, TAG, "EDGE_GESTURES_ARMED",
                "$activityName: strokes starting at a screen edge may trigger system gestures (e.g. back). " +
                    "Name the activity so it contains \"ActivityNote\" to disarm them for the stylus."
            )
        }
    }

    private fun findActivity(context: Context): android.app.Activity? {
        var current: Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun attachOverlay(host: View, api: MusnapEinkApi) {
        if (hostView !== host || overlayParent != null) return
        val parent = findOverlayParent(host)
        if (parent == null) {
            adapterState = EpdAdapterState.UNAVAILABLE
            statusReason = "No window content frame to host the pen overlay"
            OpenInkBridgeLogger.e(Subsystem.Backend, TAG, "NO_OVERLAY_PARENT", statusReason!!)
            return
        }
        overlayParent = parent

        val vto = host.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            syncOverlayBounds()
            true
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            // Dialogs and popups take window focus; never ink through them.
            windowFocused = hasFocus
            applyAutoHandWrite()
        }
        vto.addOnPreDrawListener(preDraw)
        vto.addOnWindowFocusChangeListener(focus)
        preDrawListener = preDraw
        focusListener = focus
        windowFocused = host.hasWindowFocus()
        syncOverlayBounds()
    }

    private fun createOverlaySurface(parent: ViewGroup, params: FrameLayout.LayoutParams) {
        val api = api ?: return
        val context = hostView?.context ?: return
        val surface = try {
            api.createSurfaceView(context)
        } catch (error: Throwable) {
            adapterState = EpdAdapterState.UNAVAILABLE
            statusReason = "Could not create HWSurfaceView: ${error.message}"
            OpenInkBridgeLogger.e(Subsystem.Backend, TAG, "OVERLAY_CREATE_FAILED", statusReason!!)
            return
        }
        surface.setZOrderOnTop(true)
        surface.holder.setFormat(PixelFormat.TRANSPARENT)
        surface.setOnTouchListener { _, event -> onOverlayTouch(event) }

        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (overlay !== surface) return
                clearOverlaySurface()
                configureFbSurface()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (overlay !== surface) return
                // The firmware ink lives in this surface only; the host keeps every committed stroke.
                fbSurface = null
                autoHandWriteApplied = null
                assembler.reset()
                adapterState = EpdAdapterState.DEGRADED
                statusReason = "Waiting for the pen overlay surface to be recreated"
            }
        }
        surfaceCallback = callback
        surface.holder.addCallback(callback)
        overlay = surface
        parent.addView(surface, params)
    }

    private fun destroyOverlaySurface() {
        val surface = overlay ?: return
        fbSurface?.let { fb ->
            runCatching { api?.setAutoHandWrite(fb, false) }
            runCatching { api?.setTouchListener(fb, null) }
        }
        surfaceCallback?.let(surface.holder::removeCallback)
        surface.setOnTouchListener(null)
        overlayParent?.removeView(surface)
        overlay = null
        surfaceCallback = null
        fbSurface = null
        autoHandWriteApplied = null
        assembler.reset()
    }

    private fun findOverlayParent(host: View): ViewGroup? {
        val root = host.rootView
        return root.findViewById<View>(android.R.id.content) as? FrameLayout
            ?: root as? FrameLayout
    }

    private fun configureFbSurface() {
        val api = api ?: return
        val surface = overlay ?: return
        try {
            val fb = api.fbSurface(surface) ?: throw IllegalStateException("HWSurfaceView has no FB surface")
            api.setToolTypes(fb, intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
            api.setTouchListener(fb, ahwSink)
            fbSurface = fb
            autoHandWriteApplied = null
            applyPen()
            applyAutoHandWrite()
            adapterState = EpdAdapterState.OPERATIONAL
            statusReason = null
            OpenInkBridgeLogger.i(Subsystem.Backend, TAG, "OPERATIONAL", "Firmware pen fast path bound to overlay ${region.width()}x${region.height()}")
        } catch (error: Throwable) {
            fbSurface = null
            adapterState = EpdAdapterState.UNAVAILABLE
            statusReason = "Could not configure the firmware pen: ${error.message ?: error.javaClass.simpleName}"
            OpenInkBridgeLogger.e(Subsystem.Backend, TAG, "CONFIGURE_FAILED", statusReason!!)
        }
    }

    private val ahwSink = object : MusnapEinkApi.AhwSink {
        override fun onPoint(action: Int, x: Float, y: Float, pressure: Float, tilt: Float, timestamp: Long) {
            if (inkingOffSince?.let { timestamp >= it } == true) return
            if (action == AhwAction.DOWN) findOverlayCanvas()?.beginVendorStroke()
            val stroke = assembler.onPoint(
                action, x, y, pressure, tilt, timestamp,
                offsetX = region.left.toFloat(),
                offsetY = region.top.toFloat(),
                buttons = buttonTimeline.buttonsAt(timestamp)
            ) ?: return
            handOff(stroke)
        }
    }

    private fun handOff(stroke: List<PenPoint>) {
        val overlayCanvas = findOverlayCanvas()
        when {
            overlayCanvas != null -> {
                // WebView hosts commit (and smooth) the stroke in JS, then acknowledge with
                // strokeDrawn, which arrives here as clearHardwareScribble(). Points are reported
                // relative to the canvas overlay, the same space its own touch path uses.
                val left = overlayCanvas.left.toFloat()
                val top = overlayCanvas.top.toFloat()
                overlayCanvas.dispatchCompletedStroke(
                    stroke.map { it.copy(x = it.x - left, y = it.y - top) },
                    PenPointCoordinateSpace.OVERLAY_LOCAL_PHYSICAL_PIXELS
                )
            }
            hostView is OpenInkBridgeView -> {
                // Committed through the firmware pen, the raw points reproduce the preview exactly;
                // smoothing would move the stroke at pen-up. Otherwise match the smoothing the
                // view applies to strokes it captures itself.
                val committed = if (hostRendersWithFirmwarePen()) stroke else CoreBridge.smoothStroke(stroke)
                // The firmware's ink stands in for the stroke until the view draws it.
                (hostView as OpenInkBridgeView).commitInkedStroke(committed)
            }
            else -> clearHardwareScribble()
        }
    }

    private fun findOverlayCanvas(): OpenInkBridgeOverlayCanvas? {
        val host = hostView ?: return null
        (host as? OpenInkBridgeOverlayCanvas)?.let { return it }
        val group = host as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? OpenInkBridgeOverlayCanvas)?.let { return it }
        }
        return null
    }

    /** Keeps the overlay exactly over the host's active drawing region, in window coordinates. */
    private fun syncOverlayBounds() {
        val host = hostView ?: return
        val parent = overlayParent ?: return

        val target = drawingLimit
            ?: findOverlayCanvas()?.takeIf { it.visibility == View.VISIBLE && it !== host }
                ?.let { Rect(it.left, it.top, it.right, it.bottom) }
            ?: Rect(0, 0, host.width, host.height)
        region.set(target)

        val hostLocation = IntArray(2)
        val parentLocation = IntArray(2)
        host.getLocationInWindow(hostLocation)
        parent.getLocationInWindow(parentLocation)
        val left = hostLocation[0] - parentLocation[0] - parent.paddingLeft + target.left
        val top = hostLocation[1] - parentLocation[1] - parent.paddingTop + target.top
        val width = target.width().coerceAtLeast(1)
        val height = target.height().coerceAtLeast(1)

        val wanted = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }
        val surface = overlay
        val params = surface?.layoutParams as? FrameLayout.LayoutParams
        when {
            surface == null -> createOverlaySurface(parent, wanted)
            params == null || params.width != width || params.height != height -> {
                // The firmware keeps the geometry the pen surface was first bound with: a resized
                // HWSurfaceView reports points but inks nothing. Bind a new one instead.
                if (!recreatePending) {
                    recreatePending = true
                    host.post {
                        recreatePending = false
                        if (overlay === surface) {
                            destroyOverlaySurface()
                            syncOverlayBounds()
                        }
                    }
                }
            }
            params.leftMargin != left || params.topMargin != top -> surface.layoutParams = wanted
        }

        // Visibility of the host or of a WebView's canvas overlay changes without any adapter call.
        hostShown = host.isShown
        applyAutoHandWrite()
    }

    private fun inkingActive(): Boolean =
        fbSurface != null && writingModeEnabled() && windowFocused && hostShown

    /**
     * WebView hosts toggle raw drawing on every ACTION_DOWN (inside vs. outside the canvas), which
     * suits a vendor that sees every touch. This overlay only ever receives touches inside its
     * region, so for those hosts writing mode is the canvas overlay's visibility instead.
     */
    private fun writingModeEnabled(): Boolean {
        val canvas = findOverlayCanvas()?.takeIf { it !== hostView } ?: return rawDrawingEnabled
        return canvas.visibility == View.VISIBLE
    }

    /**
     * The overlay sees the stylus events before the host does. Buttons are reported first, so a
     * host that pauses inking in its button listener gets this very stroke routed to itself.
     */
    private fun onOverlayTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        val buttons = PenButtons.fromAndroidButtonState(event.buttonState)
        buttonTimeline.record(event.eventTime, buttons)
        (hostView as? OpenInkBridgeView)?.reportStylusButtons(buttons)
        (hostView as? OpenInkBridgeView)?.reportPenActivity(event)
        val active = inkingActive()
        if (!active && event.actionMasked == MotionEvent.ACTION_DOWN) {
            // The firmware started inking this stroke before the host paused it: it is the host's.
            inkingOffSince = inkingOffSince?.let { minOf(it, event.eventTime) }
        }
        return active
    }

    private fun applyAutoHandWrite() {
        val api = api ?: return
        val fb = fbSurface ?: return
        val enabled = inkingActive()
        if (autoHandWriteApplied == enabled) return
        try {
            api.setAutoHandWrite(fb, enabled)
            autoHandWriteApplied = enabled
            inkingOffSince = if (enabled) null else SystemClock.uptimeMillis()
        } catch (error: Throwable) {
            OpenInkBridgeLogger.w(Subsystem.Backend, TAG, "AUTO_HANDWRITE_FAILED", "setAutoHandWrite($enabled) failed: ${error.message}")
        }
    }

    private fun applyPen() {
        val api = api ?: return
        val fb = fbSurface ?: return
        val density = hostView?.resources?.displayMetrics?.density ?: 1f
        try {
            if (hostRendersWithFirmwarePen()) {
                api.setPen(
                    fb,
                    penTypeFor(api, stylusTool),
                    MusnapPenMapping.previewWidthPx(strokeWidth, density, pressure = 1f),
                    MusnapPenMapping.previewColor(strokeColor)
                )
            } else {
                api.setPen(
                    fb,
                    api.penTypeSimple,
                    MusnapPenMapping.previewWidthPx(strokeWidth, density),
                    MusnapPenMapping.previewColor(strokeColor)
                )
            }
        } catch (error: Throwable) {
            OpenInkBridgeLogger.w(Subsystem.Backend, TAG, "SET_PEN_FAILED", "setAhwPen failed: ${error.message}")
        }
    }

    private fun clearOverlaySurface() {
        val holder = overlay?.holder ?: return
        try {
            val canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            holder.unlockCanvasAndPost(canvas)
        } catch (error: Throwable) {
            OpenInkBridgeLogger.w(Subsystem.Refresh, TAG, "OVERLAY_CLEAR_FAILED", "Could not clear the pen overlay: ${error.message}")
        }
    }

    override fun clearHardwareScribble() {
        val host = hostView ?: return
        if (clearScheduled) return
        clearScheduled = true
        // Clearing before the host has shown the stroke would make it blink out.
        afterNextHostFrame(host) {
            clearScheduled = false
            // A stroke in progress would lose its ink; the next hand-off clears both.
            if (!assembler.isStrokeActive) clearOverlaySurface()
        }
    }

    private fun afterNextHostFrame(host: View, action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            host.viewTreeObserver.registerFrameCommitCallback { host.post(action) }
            host.invalidate()
        } else {
            host.postOnAnimation { host.postOnAnimation(action) }
        }
    }

    override fun startStroke(tool: StylusTool, color: Int, width: Float) {
        previewPoints.clear()
        previewPaint.color = color
        previewPaint.strokeWidth = MusnapPenMapping.previewWidthPx(width, hostView?.resources?.displayMetrics?.density ?: 1f)
        hostView?.invalidate()
    }

    override fun drawPoint(point: PenPoint) {
        previewPoints.add(point)
        hostView?.invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (previewPoints.size < 2) return
        val path = Path()
        path.moveTo(previewPoints[0].x, previewPoints[0].y)
        for (i in 1 until previewPoints.size) path.lineTo(previewPoints[i].x, previewPoints[i].y)
        canvas.drawPath(path, previewPaint)
    }

    override fun endStroke() {
        previewPoints.clear()
        hostView?.invalidate()
    }

    override fun clear() {
        previewPoints.clear()
        assembler.reset()
        clearOverlaySurface()
        hostView?.invalidate()
    }

    override fun triggerFullRefresh() {
        val context = hostView?.context ?: return
        if (api?.fullRefresh(context) != true) {
            OpenInkBridgeLogger.w(Subsystem.Refresh, TAG, "FULL_REFRESH_FAILED", "applyFullScreenRefresh unavailable")
        }
    }

    override fun setRefreshMode(mode: EInkRefreshMode) {
        val host = hostView ?: return
        if (api?.setUpdateMode(host, MusnapPenMapping.updateModeConstant(mode)) != true) {
            OpenInkBridgeLogger.w(Subsystem.Refresh, TAG, "SET_REFRESH_MODE_FAILED", "Could not apply $mode")
        }
    }

    override fun setBrushStyle(color: Int, width: Float) {
        strokeColor = color
        strokeWidth = width
        applyPen()
    }

    override fun isDirectDrawingActive(): Boolean =
        fbSurface != null && adapterState == EpdAdapterState.OPERATIONAL

    /** The firmware inks the stylus only; fingers always fall through to the host's software path. */
    override fun setStylusOnly(enabled: Boolean) {}

    override fun setRawDrawingEnabled(enabled: Boolean) {
        rawDrawingEnabled = enabled
        applyAutoHandWrite()
    }

    override fun setDrawingLimit(rect: Rect?) {
        drawingLimit = rect?.let(::Rect)
        syncOverlayBounds()
    }

    override fun release() {
        hostView?.viewTreeObserver?.let { vto ->
            preDrawListener?.let(vto::removeOnPreDrawListener)
            focusListener?.let(vto::removeOnWindowFocusChangeListener)
        }
        destroyOverlaySurface()
        preDrawListener = null
        focusListener = null
        overlayParent = null
        hostView = null
        clearScheduled = false
        recreatePending = false
        previewPoints.clear()
        adapterState = EpdAdapterState.RELEASED
        statusReason = null
    }

    override fun setStylusTool(tool: StylusTool) {
        stylusTool = tool
        applyPen()
    }

    override fun renderStroke(canvas: Canvas, stroke: DrawingStroke): Boolean {
        val api = api ?: return false
        if (!api.canRenderStrokes) return false
        val density = hostView?.resources?.displayMetrics?.density
            ?: android.content.res.Resources.getSystem().displayMetrics.density
        return api.renderStroke(
            canvas,
            penTypeFor(api, stroke.tool),
            MusnapPenMapping.previewWidthPx(stroke.width, density, pressure = 1f),
            stroke.color,
            stroke.points
        )
    }

    private fun penTypeFor(api: MusnapEinkApi, tool: StylusTool): Int =
        api.penType(MusnapPenMapping.penTypeConstant(tool)) ?: api.penTypeSimple

    override fun status(): EpdAdapterStatus =
        EpdAdapterStatus(state = adapterState, reason = statusReason, capabilities = capabilities())

    private companion object {
        const val TAG = "MUSNAP"

        /** Checked by the firmware's `IreaderEdgeGesture.isHandWriteScence` against the focused activity. */
        val HANDWRITING_ACTIVITY_MARKERS = listOf("ActivityNote", "ActivityRead", "ImageGalleryActivity")
    }
}

/** Reflective handle on the firmware's `android.eink` pen API, resolved once and fail-fast. */
@SuppressLint("PrivateApi")
internal class MusnapEinkApi private constructor(
    private val surfaceViewConstructor: Constructor<*>,
    private val getFbSurface: Method,
    private val setAhwToolTypes: Method,
    private val setAhwPen: Method,
    private val setAutoHandWriteMethod: Method,
    private val setListenerV1: Method,
    private val listenerV1: Class<*>,
    private val listenerV2: ListenerV2?,
    private val setUpdateModeMethod: Method?,
    private val einkModeClass: Class<*>?,
    private val applyFullScreenRefresh: Method?,
    private val penConfig: Class<*>,
    private val renderer: PenRenderer?,
    val penTypeSimple: Int
) {
    /** The firmware pen engine (`HandPen`), driven offscreen to render committed strokes. */
    private class PenRenderer(
        val constructor: Constructor<*>,
        val setCanvasBoard: Method,
        val setStrokeWidth: Method,
        val setStrokeColor: Method,
        val onTouchEventV2: Method,
        val draw: Method,
        val handPointConstructor: Constructor<*>
    )

    val canRenderStrokes: Boolean get() = renderer != null

    fun penType(constantName: String): Int? =
        runCatching { penConfig.getField(constantName).getInt(null) }.getOrNull()

    /** Draws [points] with the firmware pen [type] into a software-backed [canvas]. */
    fun renderStroke(canvas: Canvas, type: Int, widthPx: Float, color: Int, points: List<PenPoint>): Boolean {
        val r = renderer ?: return false
        if (points.isEmpty()) return true
        return try {
            val pen = r.constructor.newInstance(type, false)
            r.setCanvasBoard.invoke(pen, canvas)
            r.setStrokeWidth.invoke(pen, widthPx)
            r.setStrokeColor.invoke(pen, color)
            points.forEachIndexed { index, p ->
                val action = when (index) {
                    0 -> AhwAction.DOWN
                    points.lastIndex -> AhwAction.UP
                    else -> AhwAction.MOVE
                }
                r.onTouchEventV2.invoke(pen, r.handPointConstructor.newInstance(action, p.x, p.y, p.timestamp, p.pressure, p.tilt))
            }
            r.draw.invoke(pen)
            true
        } catch (error: Throwable) {
            OpenInkBridgeLogger.w(Subsystem.Renderer, "MUSNAP", "RENDER_STROKE_FAILED", "${error.cause?.message ?: error.message}")
            false
        }
    }
    interface AhwSink {
        fun onPoint(action: Int, x: Float, y: Float, pressure: Float, tilt: Float, timestamp: Long)
    }

    /** V2 reports a full HandPoint, including tilt, which the V1 callback leaves out. */
    private class ListenerV2(
        val setter: Method,
        val listenerClass: Class<*>,
        val paramPoint: Field,
        val x: Field,
        val y: Field,
        val action: Field,
        val eventTime: Field,
        val pressure: Field,
        val tilt: Field
    )

    val hasListenerV2: Boolean get() = listenerV2 != null

    fun createSurfaceView(context: Context): SurfaceView =
        surfaceViewConstructor.newInstance(context) as SurfaceView

    fun fbSurface(view: SurfaceView): Any? = getFbSurface.invoke(view)

    fun setToolTypes(fb: Any, toolTypes: IntArray) {
        setAhwToolTypes.invoke(fb, toolTypes)
    }

    fun setPen(fb: Any, type: Int, widthPx: Float, color: Int) {
        setAhwPen.invoke(fb, type, widthPx, color)
    }

    fun setAutoHandWrite(fb: Any, enabled: Boolean) {
        setAutoHandWriteMethod.invoke(fb, enabled)
    }

    /** Installs [sink] (or removes the listener when null), preferring the V2 callback. */
    fun setTouchListener(fb: Any, sink: AhwSink?) {
        val v2 = listenerV2
        if (sink == null) {
            runCatching { setListenerV1.invoke(fb, null) }
            v2?.let { runCatching { it.setter.invoke(fb, null) } }
            return
        }
        if (v2 != null) {
            try {
                v2.setter.invoke(fb, proxy(v2.listenerClass) { method, args -> dispatchV2(v2, sink, method, args) })
                return
            } catch (error: Throwable) {
                OpenInkBridgeLogger.w(Subsystem.Backend, "MUSNAP", "LISTENER_V2_FAILED", "Falling back to V1 listener: ${error.message}")
            }
        }
        setListenerV1.invoke(fb, proxy(listenerV1) { method, args -> dispatchV1(sink, method, args) })
    }

    fun setUpdateMode(view: View, constantName: String): Boolean {
        val method = setUpdateModeMethod ?: return false
        val modes = einkModeClass ?: return false
        return runCatching {
            method.invoke(null, view, modes.getField(constantName).getInt(null))
            true
        }.getOrDefault(false)
    }

    fun fullRefresh(context: Context): Boolean {
        val method = applyFullScreenRefresh ?: return false
        return runCatching { method.invoke(null, context); true }.getOrDefault(false)
    }

    private fun dispatchV1(sink: AhwSink, method: Method, args: Array<out Any?>?) {
        if (method.name != "onAhwAddPoint" || args == null || args.size < 5) return
        sink.onPoint(
            action = args[0] as Int,
            x = args[1] as Float,
            y = args[2] as Float,
            pressure = args[4] as Float,
            tilt = 0f,
            timestamp = args[3] as Long
        )
    }

    private fun dispatchV2(v2: ListenerV2, sink: AhwSink, method: Method, args: Array<out Any?>?) {
        if (method.name != "onAhwAddPoint" || args == null || args.size != 1) return
        val point = v2.paramPoint.get(args[0]) ?: return
        sink.onPoint(
            action = v2.action.getInt(point),
            x = v2.x.getFloat(point),
            y = v2.y.getFloat(point),
            pressure = v2.pressure.getFloat(point),
            tilt = v2.tilt.getFloat(point),
            timestamp = v2.eventTime.getLong(point)
        )
    }

    private fun proxy(type: Class<*>, onCall: (Method, Array<out Any?>?) -> Unit): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "OpenInkBridgeAhwListener"
                else -> {
                    try {
                        onCall(method, args)
                    } catch (error: Throwable) {
                        OpenInkBridgeLogger.w(Subsystem.PenInput, "MUSNAP", "AHW_CALLBACK_FAILED", "${method.name}: ${error.message}")
                    }
                    null
                }
            }
        }
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
    }

    companion object {
        private const val PKG = "android.eink"

        fun resolve(): MusnapEinkApi {
            val surfaceView = Class.forName("$PKG.view.HWSurfaceView")
            val fbSurface = Class.forName("$PKG.view.IFBHandSurface")
            val listenerV1 = Class.forName("$PKG.view.IAhwTouchListener")
            val penConfig = Class.forName("$PKG.view.HandPenConfig")

            val listenerV2 = runCatching {
                val listenerClass = Class.forName("$PKG.view.IAhwTouchListenerV2")
                val paramClass = Class.forName("$PKG.view.IAhwTouchListenerV2\$ParamAddPoint")
                val handPoint = Class.forName("$PKG.view.HandPoint")
                ListenerV2(
                    setter = fbSurface.getMethod("setAhwTouchListenerV2", listenerClass),
                    listenerClass = listenerClass,
                    paramPoint = paramClass.getField("point"),
                    x = handPoint.getField("x"),
                    y = handPoint.getField("y"),
                    action = handPoint.getField("action"),
                    eventTime = handPoint.getField("eventtime"),
                    pressure = handPoint.getField("pressure"),
                    tilt = handPoint.getField("tilt")
                )
            }.getOrNull()

            val renderer = runCatching {
                val handPen = Class.forName("$PKG.view.HandPen")
                val handPoint = Class.forName("$PKG.view.HandPoint")
                PenRenderer(
                    constructor = handPen.getConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                    setCanvasBoard = handPen.getMethod("setCanvasBoard", Canvas::class.java),
                    setStrokeWidth = handPen.getMethod("setStrokeWidth", Float::class.javaPrimitiveType),
                    setStrokeColor = handPen.getMethod("setStrokeColor", Int::class.javaPrimitiveType),
                    onTouchEventV2 = handPen.getMethod("onTouchEventV2", handPoint),
                    draw = handPen.getMethod("draw"),
                    handPointConstructor = handPoint.getConstructor(
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType
                    )
                )
            }.getOrNull()

            val viewApi = runCatching { Class.forName("$PKG.api.ViewAPI") }.getOrNull()
            val einkApi = runCatching { Class.forName("$PKG.api.EinkAPI") }.getOrNull()

            return MusnapEinkApi(
                surfaceViewConstructor = surfaceView.getConstructor(Context::class.java),
                getFbSurface = surfaceView.getMethod("getFBSurface"),
                setAhwToolTypes = fbSurface.getMethod("setAhwToolTypes", IntArray::class.java),
                setAhwPen = fbSurface.getMethod(
                    "setAhwPen",
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ),
                setAutoHandWriteMethod = fbSurface.getMethod("setAutoHandWrite", Boolean::class.javaPrimitiveType),
                setListenerV1 = fbSurface.getMethod("setAhwTouchListener", listenerV1),
                listenerV1 = listenerV1,
                listenerV2 = listenerV2,
                setUpdateModeMethod = viewApi?.let {
                    runCatching { it.getMethod("setUpdateMode", View::class.java, Int::class.javaPrimitiveType) }.getOrNull()
                },
                einkModeClass = runCatching { Class.forName("$PKG.api.EinkMode") }.getOrNull(),
                applyFullScreenRefresh = einkApi?.let {
                    runCatching { it.getMethod("applyFullScreenRefresh", Context::class.java) }.getOrNull()
                },
                penConfig = penConfig,
                renderer = renderer,
                penTypeSimple = penConfig.getField("PEN_TYPE_SIMPLE").getInt(null)
            )
        }
    }
}
