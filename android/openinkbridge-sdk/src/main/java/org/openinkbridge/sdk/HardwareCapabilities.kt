package org.openinkbridge.sdk

import org.json.JSONArray
import org.json.JSONObject

/** How a value of [HardwareCapabilities] was established (OIP §7.1). */
enum class ValueSource(internal val wire: String) {
    /** Reported by a vendor SDK, firmware API or vendor system property. */
    VENDOR("vendor"),
    /** Looked up in the shared device table (`contracts/device-table/device-table-v1.json`). */
    DEVICE_TABLE("deviceTable"),
    /** Inferred from generic signals that can be wrong. */
    HEURISTIC("heuristic"),
    /** Unknown: the value is null. */
    NONE("none")
}

/** A value that may be inferred rather than reported, with where it came from; null if unknown. */
data class Sourced<T>(val value: T?, val source: ValueSource) {
    companion object {
        fun <T> unknown(): Sourced<T> = Sourced(null, ValueSource.NONE)
    }
}

enum class DisplayTechnology(internal val wire: String) { EINK("eink"), CONVENTIONAL("conventional") }

enum class PanelColor(internal val wire: String) { MONOCHROME("monochrome"), COLOR("color") }

data class DeviceInfo(
    /** As the system reports it; rebranded devices report the reseller's names. */
    val manufacturer: String?,
    val brand: String?,
    val model: String?,
    /** Firmware family, e.g. "onyx". */
    val firmware: Sourced<String>
)

data class DisplayInfo(
    val technology: Sourced<DisplayTechnology>,
    val color: Sourced<PanelColor>,
    /** Panel part number, when the system reports one. */
    val panelModel: String?,
    /** Full panel size in physical pixels, in its natural orientation. */
    val widthPx: Int?,
    val heightPx: Int?,
    /** Pixel density as the platform reports it, which can differ from the panel's. */
    val ppi: Float?
)

/** The stylus digitizer; null where the platform only tells once the pen is used. */
data class DigitizerInfo(
    val stylus: Boolean?,
    val pressure: Boolean?,
    val tilt: Boolean?,
    val hover: Boolean?,
    val eraser: Boolean?
)

data class EpdcInfo(
    /** Refresh modes the backend maps to something real; empty without refresh control. */
    val refreshModes: List<EInkRefreshMode>,
    val fullRefresh: Boolean
)

/** What a backend's low-latency ink can show. */
sealed interface LiveInkColors {
    /** Any colour the panel can show. */
    object Full : LiveInkColors
    /** Grey levels only. */
    object Greyscale : LiveInkColors
    /** Only these opaque ARGB colours; others are mapped to one of them. */
    data class Fixed(val colors: List<Int>) : LiveInkColors
}

/** The backend's low-latency ink path. */
data class LiveInkInfo(
    /** Null when unknown. */
    val colors: LiveInkColors?
)

/**
 * What the device is and what the active backend does with it (OIP §7.1, schema version 2).
 * Values that cannot be established are null.
 */
data class HardwareCapabilities(
    val device: DeviceInfo,
    val display: DisplayInfo,
    val digitizer: DigitizerInfo,
    /** The active backend, e.g. "onyx"; null without one. */
    val backend: String?,
    val epdc: EpdcInfo,
    /** Null without a low-latency ink path. */
    val liveInk: LiveInkInfo?,
    /** Vendor-specific values keyed `<vendor>.<name>`; they may change between releases. */
    val extras: Map<String, Any?>
) {
    fun toJson(): String = toJsonObject().toString()

    internal fun toJsonObject(): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("device", JSONObject().apply {
            put("manufacturer", device.manufacturer.orNull())
            put("brand", device.brand.orNull())
            put("model", device.model.orNull())
            put("runtime", "android")
            put("firmware", device.firmware.toJson { it })
        })
        put("display", JSONObject().apply {
            put("technology", display.technology.toJson { it.wire })
            put("color", display.color.toJson { it.wire })
            put("panelModel", display.panelModel.orNull())
            put("widthPx", display.widthPx.orNull())
            put("heightPx", display.heightPx.orNull())
            put("ppi", display.ppi?.toDouble().orNull())
        })
        put("digitizer", JSONObject().apply {
            put("stylus", digitizer.stylus.orNull())
            put("pressure", digitizer.pressure.orNull())
            put("tilt", digitizer.tilt.orNull())
            put("hover", digitizer.hover.orNull())
            put("eraser", digitizer.eraser.orNull())
        })
        put("backend", backend.orNull())
        put("epdc", JSONObject().apply {
            put("refreshModes", JSONArray(REFRESH_MODE_ORDER.filter(epdc.refreshModes::contains).map { it.name }))
            put("fullRefresh", epdc.fullRefresh)
        })
        put("liveInk", liveInk?.let { ink ->
            JSONObject().put("colors", ink.colors?.toJson().orNull())
        }.orNull())
        put("extras", JSONObject().apply { extras.forEach { (key, value) -> put(key, value.orNull()) } })
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private val REFRESH_MODE_ORDER = listOf(EInkRefreshMode.SPEED, EInkRefreshMode.BALANCED, EInkRefreshMode.QUALITY)
    }
}

private fun Any?.orNull(): Any = this ?: JSONObject.NULL

private fun <T> Sourced<T>.toJson(wire: (T) -> String): JSONObject = JSONObject().apply {
    put("value", value?.let(wire).orNull())
    put("source", if (value == null) ValueSource.NONE.wire else source.wire)
}

private fun LiveInkColors.toJson(): JSONObject = when (this) {
    LiveInkColors.Full -> JSONObject().put("kind", "full")
    LiveInkColors.Greyscale -> JSONObject().put("kind", "greyscale")
    is LiveInkColors.Fixed -> JSONObject().put("kind", "fixed")
        .put("values", JSONArray(colors.map { String.format("#%06X", it and 0xFFFFFF) }))
}

/** What the operating system reports about the device. */
internal data class BuildFacts(val manufacturer: String?, val brand: String?, val model: String?)

/** What a vendor SDK or firmware API reports; null fields are not reported. */
internal data class VendorFacts(
    val firmware: String? = null,
    val technology: DisplayTechnology? = null,
    val color: PanelColor? = null,
    val panelModel: String? = null,
    val extras: Map<String, Any?> = emptyMap()
)

internal data class DisplayFacts(val widthPx: Int?, val heightPx: Int?, val ppi: Float?)

internal data class BackendFacts(val id: String, val capabilities: EpdAdapterCapabilities)

/** The shared device table (`contracts/device-table/device-table-v1.json`). */
internal class DeviceTable(private val entries: List<Entry>) {
    data class Entry(
        val match: Map<String, String>,
        val firmware: String?,
        val technology: DisplayTechnology?,
        val color: PanelColor?
    )

    data class Facts(val firmware: String?, val technology: DisplayTechnology?, val color: PanelColor?)

    /** Each field from the first entry matching [keys] (case-insensitive) that sets it. */
    fun lookup(keys: Map<String, String?>): Facts {
        val matching = entries.filter { entry ->
            entry.match.all { (key, expected) -> keys[key]?.equals(expected, ignoreCase = true) == true }
        }
        return Facts(
            firmware = matching.firstNotNullOfOrNull { it.firmware },
            technology = matching.firstNotNullOfOrNull { it.technology },
            color = matching.firstNotNullOfOrNull { it.color }
        )
    }

    companion object {
        const val RESOURCE = "device-table-v1.json"
        val EMPTY = DeviceTable(emptyList())

        fun parse(json: String): DeviceTable {
            val root = JSONObject(json)
            val devices = root.optJSONArray("devices") ?: JSONArray()
            return DeviceTable((0 until devices.length()).mapNotNull { index ->
                val entry = devices.optJSONObject(index) ?: return@mapNotNull null
                val match = entry.optJSONObject("match") ?: return@mapNotNull null
                Entry(
                    match = match.keys().asSequence().associateWith { match.getString(it) },
                    firmware = entry.optString("firmware").takeIf { it.isNotEmpty() },
                    technology = DisplayTechnology.values().firstOrNull { it.wire == entry.optString("technology") },
                    color = PanelColor.values().firstOrNull { it.wire == entry.optString("color") }
                )
            })
        }
    }
}

/** Builds [HardwareCapabilities] from what each source reports: vendor first, then the device table. */
internal object HardwareCapabilitiesAssembler {
    fun assemble(
        build: BuildFacts,
        vendor: VendorFacts?,
        table: DeviceTable,
        display: DisplayFacts,
        digitizer: DigitizerInfo,
        backend: BackendFacts?
    ): HardwareCapabilities {
        val tabled = table.lookup(
            mapOf(
                "manufacturer" to build.manufacturer,
                "brand" to build.brand,
                "model" to build.model,
                "panelModel" to vendor?.panelModel,
                "firmware" to vendor?.firmware
            )
        )
        val color = sourced(vendor?.color, tabled.color)
        return HardwareCapabilities(
            device = DeviceInfo(build.manufacturer, build.brand, build.model, sourced(vendor?.firmware, tabled.firmware)),
            display = DisplayInfo(
                technology = sourced(vendor?.technology, tabled.technology),
                color = color,
                panelModel = vendor?.panelModel,
                widthPx = display.widthPx,
                heightPx = display.heightPx,
                ppi = display.ppi
            ),
            digitizer = digitizer,
            backend = backend?.id,
            epdc = EpdcInfo(
                refreshModes = if (backend?.capabilities?.refreshModeControl == true) EInkRefreshMode.values().toList() else emptyList(),
                fullRefresh = backend?.capabilities?.fullRefresh == true
            ),
            liveInk = backend?.capabilities?.takeIf { it.directDrawing }?.let { LiveInkInfo(liveInkColors(it.liveInkColors, color.value)) },
            extras = vendor?.extras.orEmpty()
        )
    }

    /** A monochrome panel shows greys only, whatever the backend could draw. */
    private fun liveInkColors(reported: LiveInkColors?, panel: PanelColor?): LiveInkColors? = when {
        panel == PanelColor.MONOCHROME && (reported == null || reported == LiveInkColors.Full) -> LiveInkColors.Greyscale
        else -> reported
    }

    private fun <T> sourced(vendor: T?, tabled: T?): Sourced<T> = when {
        vendor != null -> Sourced(vendor, ValueSource.VENDOR)
        tabled != null -> Sourced(tabled, ValueSource.DEVICE_TABLE)
        else -> Sourced.unknown()
    }
}
