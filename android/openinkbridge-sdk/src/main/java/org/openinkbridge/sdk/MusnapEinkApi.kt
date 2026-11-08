package org.openinkbridge.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
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
