package org.openinkbridge.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCapabilitiesTest {
    private fun resource(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
        "$name was not packaged for tests"
    }.bufferedReader().use { it.readText() }

    private val documents: JSONObject by lazy { JSONObject(resource("hardware-capabilities-v2.json")).getJSONObject("documents") }
    private val bridgeContract: JSONObject by lazy { JSONObject(resource("bridge-protocol-v1.json")) }
    private val table: DeviceTable by lazy { DeviceTable.parse(resource(DeviceTable.RESOURCE)) }

    private val unknownDigitizer = DigitizerInfo(null, null, null, null, null)
    private val noDisplay = DisplayFacts(null, null, null)
    private val vendorBackend = EpdAdapterCapabilities(directDrawing = true, refreshModeControl = true, fullRefresh = true)

    /** Compares JSON by structure, numbers by value (`320` equals `320.0`). */
    private fun assertSameJson(expected: JSONObject, actual: JSONObject) {
        assertEquals(normalized(expected), normalized(actual))
    }

    private fun normalized(value: Any?): Any? = when {
        value === JSONObject.NULL -> null
        value is JSONObject -> value.keys().asSequence().associateWith { normalized(value.get(it)) }
        value is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        value is Number -> value.toDouble()
        else -> value
    }

    @Test
    fun vendorReportedFirmwareWithFixedLiveInkMatchesTheContract() {
        val capabilities = HardwareCapabilitiesAssembler.assemble(
            build = BuildFacts("Chitech", "Byybuo", "TAB-Executive"),
            vendor = VendorFacts(
                firmware = "ireader",
                technology = DisplayTechnology.EINK,
                panelModel = "ED070KC4",
                extras = mapOf("ireader.platform" to "zhangyue_MTK8781_14.0")
            ),
            table = DeviceTable(listOf(DeviceTable.Entry(mapOf("panelModel" to "ED070KC4"), null, null, PanelColor.MONOCHROME))),
            display = DisplayFacts(1264, 1680, 320f),
            digitizer = DigitizerInfo(stylus = true, pressure = true, tilt = true, hover = null, eraser = null),
            backend = BackendFacts(
                "ireader",
                vendorBackend.copy(liveInkColors = LiveInkColors.Fixed(listOf(0xFF000000.toInt(), 0xFF505050.toInt(), 0xFFFFFFFF.toInt())))
            )
        )
        assertSameJson(documents.getJSONObject("ireaderTablet"), capabilities.toJsonObject())
    }

    @Test
    fun onyxFromTheDeviceTableAndVendorColourMatchesTheContract() {
        val capabilities = HardwareCapabilitiesAssembler.assemble(
            build = BuildFacts("ONYX", "ONYX", "contract-model"),
            vendor = VendorFacts(color = PanelColor.MONOCHROME, extras = mapOf("onyx.colorType" to 0)),
            table = table,
            display = DisplayFacts(1404, 1872, 227f),
            digitizer = DigitizerInfo(stylus = true, pressure = true, tilt = false, hover = null, eraser = null),
            backend = BackendFacts("onyx", vendorBackend)
        )
        // A monochrome panel caps a backend whose live ink colours are unknown to greys.
        assertSameJson(documents.getJSONObject("onyxMonochrome"), capabilities.toJsonObject())
    }

    @Test
    fun unknownDeviceReportsNullsRatherThanGuesses() {
        val capabilities = HardwareCapabilitiesAssembler.assemble(BuildFacts(null, null, null), null, table, noDisplay, unknownDigitizer, null)
        val expected = JSONObject(documents.getJSONObject("unknownDevice").toString())
        expected.getJSONObject("device").put("runtime", "android")
        assertSameJson(expected, capabilities.toJsonObject())
    }

    @Test
    fun vendorValuesWinOverTheDeviceTable() {
        val capabilities = HardwareCapabilitiesAssembler.assemble(
            BuildFacts("onyx", null, null), VendorFacts(firmware = "custom"), table, noDisplay, unknownDigitizer, null
        )
        assertEquals(Sourced("custom", ValueSource.VENDOR), capabilities.device.firmware)
        assertEquals(Sourced(DisplayTechnology.EINK, ValueSource.DEVICE_TABLE), capabilities.display.technology)
    }

    @Test
    fun deviceTableMatchesCaseInsensitivelyOnEveryKey() {
        assertEquals("onyx", table.lookup(mapOf("manufacturer" to "onyx")).firmware)
        assertNull(table.lookup(mapOf("manufacturer" to "Onyx International")).firmware)
        val both = DeviceTable(listOf(DeviceTable.Entry(mapOf("brand" to "a", "model" to "b"), "x", null, null)))
        assertNull(both.lookup(mapOf("brand" to "A")).firmware)
        assertEquals("x", both.lookup(mapOf("brand" to "A", "model" to "B")).firmware)
    }

    @Test
    fun backendWithoutLowLatencyInkHasNoLiveInk() {
        val capabilities = HardwareCapabilitiesAssembler.assemble(
            BuildFacts(null, null, null), null, table, noDisplay, unknownDigitizer, BackendFacts("canvas", EpdAdapterCapabilities())
        )
        assertEquals("canvas", capabilities.backend)
        assertNull(capabilities.liveInk)
        assertTrue(capabilities.epdc.refreshModes.isEmpty())
    }

    @Test
    fun bridgeRequestAndReplyMatchTheContract() {
        val request = BridgeProtocol.parseCommand(bridgeContract.getJSONObject("getHardwareCapabilities").toString())
        assertTrue(request is GetHardwareCapabilitiesCommand)
        val route = request!!.route
        assertEquals(BridgeSessionRoute("session-contract", "canvas-contract"), route)

        val payload = HardwareCapabilitiesAssembler.assemble(BuildFacts(null, null, null), null, table, noDisplay, unknownDigitizer, null)
        val reply = JSONObject(BridgeProtocol.hardwareCapabilities(route, payload.toJsonObject()))
        assertSameJson(bridgeContract.getJSONObject("hardwareCapabilities"), reply)
    }
}
