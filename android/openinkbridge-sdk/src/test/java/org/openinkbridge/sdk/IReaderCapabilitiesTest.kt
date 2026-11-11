package org.openinkbridge.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class IReaderCapabilitiesTest {
    private fun resource(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
        "$name was not packaged for tests"
    }.bufferedReader().use { it.readText() }

    private fun normalized(value: Any?): Any? = when {
        value === JSONObject.NULL -> null
        value is JSONObject -> value.keys().asSequence().associateWith { normalized(value.get(it)) }
        value is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        value is Number -> value.toDouble()
        else -> value
    }

    /** What the probe and adapter report on the iReader tablet the contract was taken from. */
    @Test
    fun probeFactsAndTheDeviceTableGiveTheContractDocument() {
        val capabilities = HardwareCapabilitiesAssembler.assemble(
            build = BuildFacts("Chitech", "Byybuo", "TAB-Executive"),
            vendor = VendorFacts(
                firmware = "ireader",
                technology = DisplayTechnology.EINK,
                panelModel = "ED070KC4",
                extras = mapOf("ireader.platform" to "zhangyue_MTK8781_14.0")
            ),
            table = DeviceTable.parse(resource(DeviceTable.RESOURCE)),
            display = DisplayFacts(1264, 1680, 320f),
            digitizer = DigitizerInfo(stylus = true, pressure = true, tilt = true, hover = null, eraser = null),
            backend = BackendFacts(
                "ireader",
                EpdAdapterCapabilities(
                    directDrawing = true,
                    refreshModeControl = true,
                    fullRefresh = true,
                    // HandPenConfig.PEN_COLOR_BLACK, _GRAY and _WHITE on that firmware.
                    liveInkColors = LiveInkColors.Fixed(listOf(-16777216, -11513776, -1))
                )
            )
        )
        val expected = JSONObject(resource("hardware-capabilities-v2.json")).getJSONObject("documents").getJSONObject("ireaderTablet")
        assertEquals(normalized(expected), normalized(capabilities.toJsonObject()))
    }
}
