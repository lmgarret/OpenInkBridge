package org.openinkbridge.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout

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
    // Where the firmware must not ink, in the host's local px. The clip keeping it out is in the
    // overlay's coordinates: it is set for the current pen surface, region and regions, null if none.
    private var excludedRegions: List<Rect> = emptyList()
    private var clipRegion: Rect? = null
    private var clipExcluded: List<Rect>? = null
    // The overlay's current stroke started in an excluded region and belongs to the views below.
    private var strokeExcluded = false
    // Firmware points of a stroke that started in an excluded region are dropped until its UP.
    private var droppingAhwStroke = false
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

    private var panelEraser: MusnapEinkApi.PanelEraser? = null
    private val panelEraserFeed = PanelEraserFeed { action, x, y -> onEraserThread { touch(action, x, y) } }
    private var eraserThread: HandlerThread? = null
    private var eraserHandler: Handler? = null
    private val hostLocation = IntArray(2)
    private val overlayLocation = IntArray(2)

    private fun capabilities() = EpdAdapterCapabilities(
        directDrawing = true,
        refreshModeControl = true,
        fullRefresh = true,
        drawingLimit = true,
        rawDrawingToggle = true,
        hardwareScribbleHandoff = true,
        vendorStrokeRendering = api?.canRenderStrokes == true,
        panelErase = true
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
                dropFbSurface()
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
        dropFbSurface()
    }

    /** Forgets everything bound to the overlay's firmware surface, which is gone or being replaced. */
    private fun dropFbSurface() {
        fbSurface = null
        panelEraser = null
        panelEraserFeed.reset()
        autoHandWriteApplied = null
        clipRegion = null
        clipExcluded = null
        droppingAhwStroke = false
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
            // Built now rather than at the first erase: creating its view makes the panel blink.
            if (panelEraser == null) {
                panelEraser = hostView?.let { runCatching { api.createPanelEraser(it.context, fb) }.getOrNull() }
            }
            applyPen()
            applyAutoHandWrite()
            applyExclusionClip()
            adapterState = EpdAdapterState.OPERATIONAL
            statusReason = null
            OpenInkBridgeLogger.i(Subsystem.Backend, TAG, "OPERATIONAL", "Firmware pen fast path bound to overlay ${region.width()}x${region.height()}")
        } catch (error: Throwable) {
            dropFbSurface()
            adapterState = EpdAdapterState.UNAVAILABLE
            statusReason = "Could not configure the firmware pen: ${error.message ?: error.javaClass.simpleName}"
            OpenInkBridgeLogger.e(Subsystem.Backend, TAG, "CONFIGURE_FAILED", statusReason!!)
        }
    }

    private val ahwSink = object : MusnapEinkApi.AhwSink {
        override fun onPoint(action: Int, x: Float, y: Float, pressure: Float, tilt: Float, timestamp: Long) {
            if (inkingOffSince?.let { timestamp >= it } == true) return
            // The clip keeps the firmware from inking there; should it report such a stroke
            // anyway, it is the views' below, not the host's.
            if (action == AhwAction.DOWN) droppingAhwStroke = inExcludedRegion(x + region.left, y + region.top)
            if (droppingAhwStroke) {
                if (action == AhwAction.UP) {
                    droppingAhwStroke = false
                    clearHardwareScribble()
                }
                return
            }
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
        applyExclusionClip()

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
        // A stroke starting in an excluded region falls through to the views under the overlay,
        // which then see the rest of it; it is not pen activity for the host.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            strokeExcluded = inExcludedRegion(event.x + region.left, event.y + region.top)
        }
        if (strokeExcluded) return false
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

    private fun inExcludedRegion(hostX: Float, hostY: Float): Boolean =
        excludedRegions.any { it.contains(hostX.toInt(), hostY.toInt()) }

    /**
     * Confines the firmware's ink to the overlay minus the excluded regions. The clip is in the
     * overlay's coordinates, so it is set again whenever the overlay moves or is bound anew.
     */
    private fun applyExclusionClip() {
        val api = api ?: return
        val fb = fbSurface ?: return
        if (!api.canClip) return
        if (clipExcluded === excludedRegions && clipRegion == region) return
        // No clip on this surface yet, and none needed.
        if (clipExcluded == null && excludedRegions.isEmpty()) return
        val clip = Path().apply { addRect(0f, 0f, region.width().toFloat(), region.height().toFloat(), Path.Direction.CW) }
        for (excluded in excludedRegions) {
            val hole = Path().apply {
                addRect(
                    (excluded.left - region.left).toFloat(), (excluded.top - region.top).toFloat(),
                    (excluded.right - region.left).toFloat(), (excluded.bottom - region.top).toFloat(),
                    Path.Direction.CW
                )
            }
            clip.op(hole, Path.Op.DIFFERENCE)
        }
        // Recorded even if it fails: retried on every frame, it would only fail again.
        clipRegion = Rect(region)
        clipExcluded = excludedRegions
        try {
            api.setClip(fb, clip)
        } catch (error: Throwable) {
            OpenInkBridgeLogger.w(Subsystem.Backend, TAG, "EXCLUSION_CLIP_FAILED", "setClip failed: ${error.cause?.message ?: error.message}")
        }
    }

    override fun setExcludedRegions(regions: List<Rect>) {
        excludedRegions = regions.map(::Rect)
        applyExclusionClip()
        if (api?.canClip == false) {
            OpenInkBridgeLogger.w(Subsystem.Backend, TAG, "EXCLUSION_CLIP_UNAVAILABLE", "Firmware has no IFBHandSurface.setClip: live ink is not kept out of excluded regions")
        }
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
        eraserThread?.quitSafely()
        eraserThread = null
        eraserHandler = null
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


    /**
     * The firmware eraser's DOWN waits for the panel while it is still updating, up to ~170 ms. Its
     * calls run in order on a thread of their own, so a wait delays the erase, not the touch
     * events behind it.
     */
    private fun onEraserThread(call: MusnapEinkApi.PanelEraser.() -> Unit) {
        val eraser = panelEraser ?: return
        val handler = eraserHandler ?: HandlerThread("musnap-eraser").let { thread ->
            thread.start()
            eraserThread = thread
            Handler(thread.looper).also { eraserHandler = it }
        }
        handler.post {
            try {
                eraser.call()
            } catch (error: Throwable) {
                OpenInkBridgeLogger.w(Subsystem.PenInput, TAG, "PANEL_ERASER_FAILED", "${error.cause?.message ?: error.message}")
            }
        }
    }

    override fun panelErasePath(): List<PointF> = panelEraserFeed.trace.map { PointF(it[0], it[1]) }

    override fun eraseOnPanel(event: MotionEvent, radiusPx: Int, background: Bitmap?): Boolean {
        val api = api ?: return false
        val fb = fbSurface ?: return false
        val host = hostView ?: return false
        val surface = overlay ?: return false
        return try {
            if (panelEraser == null) panelEraser = api.createPanelEraser(host.context, fb)
            val history = event.historySize
            val fromX = if (history > 0) event.getHistoricalX(0) else event.x
            val fromY = if (history > 0) event.getHistoricalY(0) else event.y
            val start = {
                host.getLocationOnScreen(hostLocation)
                surface.getLocationOnScreen(overlayLocation)
                // The eraser works in overlay coordinates and the background is in the host's.
                val dx = hostLocation[0] - overlayLocation[0]
                val dy = hostLocation[1] - overlayLocation[1]
                onEraserThread { start(background, -dx, -dy, radiusPx) }
                dx to dy
            }
            // Each call redraws the panel under the eraser, so not every point of a batch: a few
            // keep a quick curve a curve instead of chords between the batches' last points.
            for (i in batchPicks(history)) {
                panelEraserFeed.onTouch(MotionEvent.ACTION_MOVE, event.getHistoricalX(i), event.getHistoricalY(i), start, fromX, fromY)
            }
            panelEraserFeed.onTouch(event.actionMasked, event.x, event.y, start, fromX, fromY)
            true
        } catch (error: Throwable) {
            panelEraser = null
            panelEraserFeed.reset()
            OpenInkBridgeLogger.w(Subsystem.PenInput, TAG, "PANEL_ERASER_FAILED", "${error.cause?.message ?: error.message}")
            false
        }
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
