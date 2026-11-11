package org.openinkbridge.sdk

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent

/**
 * What the device's hardware is (OIP §7.1), for apps that adapt to the screen and pen they run
 * on. Values that cannot be established are null; see [Sourced] for how inferred ones were found.
 */
object OpenInkBridgeDevice {
    private const val TAG = "DEVICE"

    /**
     * Vendor probes, tried in order until one recognises the device. Each runs only the checks
     * its vendor needs and returns null elsewhere.
     */
    private val vendorProbes: List<(BuildFacts) -> VendorFacts?> = listOf(OnyxDeviceProbe::probe)

    @Volatile private var cachedVendor: Pair<BuildFacts, VendorFacts?>? = null
    private val table: DeviceTable by lazy(::loadDeviceTable)

    /** The device's hardware, without a backend: [HardwareCapabilities.backend] is null. */
    @JvmStatic
    fun hardwareCapabilities(context: Context): HardwareCapabilities = hardwareCapabilities(context, null)

    /** The device's hardware and what [adapter] does with it. */
    internal fun hardwareCapabilities(context: Context, adapter: EpdAdapter?): HardwareCapabilities {
        val build = BuildFacts(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
        val backend = adapter?.let { active ->
            val capabilities = (active as? EpdAdapterIntrospection)?.status()?.capabilities ?: EpdAdapterCapabilities()
            BackendFacts(active.backendId, capabilities)
        }
        return HardwareCapabilitiesAssembler.assemble(
            build = build,
            vendor = vendorFacts(build),
            table = table,
            display = displayFacts(context),
            digitizer = digitizer(),
            backend = backend
        )
    }

    private fun vendorFacts(build: BuildFacts): VendorFacts? {
        cachedVendor?.let { (forBuild, facts) -> if (forBuild == build) return facts }
        val facts = vendorProbes.firstNotNullOfOrNull { probe ->
            runCatching { probe(build) }.onFailure { error ->
                OpenInkBridgeLogger.w(Subsystem.Backend, TAG, "VENDOR_PROBE_FAILED", "${error.message}")
            }.getOrNull()
        }
        cachedVendor = build to facts
        return facts
    }

    private fun loadDeviceTable(): DeviceTable = runCatching {
        val stream = OpenInkBridgeDevice::class.java.classLoader?.getResourceAsStream(DeviceTable.RESOURCE)
            ?: return@runCatching DeviceTable.EMPTY
        DeviceTable.parse(stream.bufferedReader().use { it.readText() })
    }.getOrElse { error ->
        OpenInkBridgeLogger.w(Subsystem.Backend, TAG, "DEVICE_TABLE_UNAVAILABLE", "${error.message}")
        DeviceTable.EMPTY
    }

    private fun displayFacts(context: Context): DisplayFacts {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayFacts(null, null, null)
        // The mode's size is the panel's, in its natural orientation.
        val mode = display.mode
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DisplayFacts(
            widthPx = mode.physicalWidth,
            heightPx = mode.physicalHeight,
            ppi = metrics.xdpi.takeIf { it > 0f }
        )
    }

    /**
     * Android lists a stylus and the axes it reports, but not whether it hovers or has an eraser
     * end until it is used: those stay unknown.
     */
    private fun digitizer(): DigitizerInfo {
        val styluses = InputDevice.getDeviceIds().asList().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.supportsSource(InputDevice.SOURCE_STYLUS) }
        if (styluses.isEmpty()) return DigitizerInfo(stylus = false, pressure = false, tilt = false, hover = null, eraser = null)
        fun reports(axis: Int) = styluses.any { it.getMotionRange(axis, InputDevice.SOURCE_STYLUS) != null }
        return DigitizerInfo(
            stylus = true,
            pressure = reports(MotionEvent.AXIS_PRESSURE),
            tilt = reports(MotionEvent.AXIS_TILT),
            hover = null,
            eraser = null
        )
    }
}

/** Onyx: the Boox SDK reports the panel's colour type (0 for monochrome). */
internal object OnyxDeviceProbe {
    fun probe(build: BuildFacts): VendorFacts? {
        if (build.manufacturer?.contains("onyx", ignoreCase = true) != true) return null
        val colorType = runCatching {
            val device = Class.forName("com.onyx.android.sdk.device.Device").getMethod("currentDevice").invoke(null)
            device!!.javaClass.getMethod("getColorType").invoke(device) as Int
        }.getOrNull() ?: return null
        return VendorFacts(
            firmware = "onyx",
            technology = DisplayTechnology.EINK,
            color = if (colorType > 0) PanelColor.COLOR else PanelColor.MONOCHROME,
            extras = mapOf("onyx.colorType" to colorType)
        )
    }
}
