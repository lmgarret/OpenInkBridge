package org.openinkbridge.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private const val TAG = "MUSNAP"

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
            OpenInkBridgeLogger.w(Subsystem.Renderer, TAG, "RENDER_STROKE_FAILED", "${error.cause?.message ?: error.message}")
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
                OpenInkBridgeLogger.w(Subsystem.Backend, TAG, "LISTENER_V2_FAILED", "Falling back to V1 listener: ${error.message}")
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

    /**
     * The firmware's HWEraser in its quick style, which paints a background straight on the panel
     * under the pen. Its pictures need an HWEraserView; one that is never shown does. Not
     * HandEraser: its only usable style paints plain white, and its static `setStyle` persists
     * system-wide (`persist.eraser.style`); the other style blocks the caller for seconds.
     */
    fun createPanelEraser(context: Context, fb: Any): PanelEraser {
        val int = Int::class.javaPrimitiveType
        val viewType = Class.forName("$PKG.view.HWEraserView")
        val surfaceType = Class.forName("$PKG.view.HWEraserView\$SurfacePicture")
        val fbType = Class.forName("$PKG.view.HWEraserView\$FBPicture")
        val type = Class.forName("$PKG.view.HWEraser")
        val view = viewType.getConstructor(Context::class.java).newInstance(context)
        val surfacePicture = surfaceType.getConstructor(viewType).newInstance(view)
        surfaceType.getMethod("bindColor", int).invoke(surfacePicture, Color.TRANSPARENT)
        val picture = fbType.getConstructor().newInstance()
        fbType.getMethod("bindColor", int).invoke(picture, Color.WHITE)
        val constructor = type.getConstructor(Class.forName("$PKG.view.IFBHandSurface"), surfaceType, fbType)
        val style = type.getMethod("setStyle", int)
        val quick = type.getField("STYLE_QUICK").getInt(null)
        // Each one takes about 200 ms to build (the firmware logs a missing native method on the
        // way), so both are built once: one follows the pen, the other retraces its start.
        val (live, retrace) = List(2) { constructor.newInstance(fb, surfacePicture, picture).also { style.invoke(it, quick) } }
        return PanelEraser(
            picture,
            live,
            retrace,
            bindColor = fbType.getMethod("bindColor", int),
            bindBitmap = fbType.getMethod("bindBitmap", Bitmap::class.java, Boolean::class.javaPrimitiveType),
            pictureLocation = fbType.getMethod("setViewLocation", int, int),
            radius = type.getMethod("setRadius", int),
            touch = type.getMethod("onTouchEvent", int, int, int)
        )
    }

    class PanelEraser(
        private val picture: Any,
        private val live: Any,
        private val retracer: Any,
        private val bindColor: Method,
        private val bindBitmap: Method,
        private val pictureLocation: Method,
        private val radius: Method,
        private val touch: Method
    ) {
        /**
         * Sets what the erase uncovers: [background], or white. The eraser copies the background
         * from its own coordinates plus ([offsetX], [offsetY]): the overlay's position in it.
         */
        fun start(background: Bitmap?, offsetX: Int, offsetY: Int, radiusPx: Int) {
            if (background != null) bindBitmap.invoke(picture, background, false) else bindColor.invoke(picture, Color.WHITE)
            pictureLocation.invoke(picture, offsetX, offsetY)
            radius.invoke(live, radiusPx)
            radius.invoke(retracer, radiusPx)
        }

        /** Erases along [points] with the second eraser, leaving the live one where it is. */
        fun retrace(points: List<IntArray>) {
            touch.invoke(retracer, MotionEvent.ACTION_DOWN, points[0][0], points[0][1])
            // As in a live erase: the firmware erases from the first move on.
            for (p in points) touch.invoke(retracer, MotionEvent.ACTION_MOVE, p[0], p[1])
        }

        // One point per event, as the notes app does. An UP repaints the panel from the eraser's
        // view, flashing the erased ink back until the host redraws, so the eraser never sees it.
        fun touch(action: Int, x: Int, y: Int) {
            if (action != MotionEvent.ACTION_UP) touch.invoke(live, action, x, y)
        }
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
                        OpenInkBridgeLogger.w(Subsystem.PenInput, TAG, "AHW_CALLBACK_FAILED", "${method.name}: ${error.message}")
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
