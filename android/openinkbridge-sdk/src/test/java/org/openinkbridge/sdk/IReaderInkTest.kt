package org.openinkbridge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IReaderInkTest {

    @Test
    fun assemblesStrokeFromDownMoveUp() {
        val assembler = AhwStrokeAssembler()

        assertNull(assembler.onPoint(AhwAction.DOWN, 1f, 2f, 0.2f, 0f, 10L))
        assertTrue(assembler.isStrokeActive)
        assertNull(assembler.onPoint(AhwAction.MOVE, 3f, 4f, 0.5f, 1f, 20L))
        val stroke = assembler.onPoint(AhwAction.UP, 5f, 6f, 0.1f, 2f, 30L)!!

        assertEquals(listOf(1f, 3f, 5f), stroke.map { it.x })
        assertEquals(listOf(10L, 20L, 30L), stroke.map { it.timestamp })
        assertEquals(2f, stroke.last().tilt, 0f)
        assertFalse(assembler.isStrokeActive)
    }

    @Test
    fun translatesOverlayLocalPointsIntoHostSpace() {
        val assembler = AhwStrokeAssembler()

        assembler.onPoint(AhwAction.DOWN, 10f, 20f, 0.5f, 0f, 1L, offsetX = 100f, offsetY = 200f)
        val stroke = assembler.onPoint(AhwAction.UP, 11f, 21f, 0.5f, 0f, 2L, offsetX = 100f, offsetY = 200f)!!

        assertEquals(110f, stroke[0].x, 0f)
        assertEquals(220f, stroke[0].y, 0f)
        assertEquals(111f, stroke[1].x, 0f)
        assertEquals(221f, stroke[1].y, 0f)
    }

    @Test
    fun strokeWithoutDownStartsOnFirstMove() {
        val assembler = AhwStrokeAssembler()

        assertNull(assembler.onPoint(AhwAction.MOVE, 1f, 1f, 0.5f, 0f, 1L))
        val stroke = assembler.onPoint(AhwAction.UP, 2f, 2f, 0.5f, 0f, 2L)!!

        assertEquals(2, stroke.size)
    }

    @Test
    fun strayUpWithoutStrokeIsIgnored() {
        assertNull(AhwStrokeAssembler().onPoint(AhwAction.UP, 1f, 1f, 0.5f, 0f, 1L))
    }

    @Test
    fun cancelDiscardsTheStroke() {
        val assembler = AhwStrokeAssembler()

        assembler.onPoint(AhwAction.DOWN, 1f, 1f, 0.5f, 0f, 1L)
        assembler.onPoint(AhwAction.CANCEL, 2f, 2f, 0.5f, 0f, 2L)

        assertFalse(assembler.isStrokeActive)
        assertNull(assembler.onPoint(AhwAction.UP, 3f, 3f, 0.5f, 0f, 3L))
    }

    @Test
    fun newDownRestartsAnUnfinishedStroke() {
        val assembler = AhwStrokeAssembler()

        assembler.onPoint(AhwAction.DOWN, 1f, 1f, 0.5f, 0f, 1L)
        assembler.onPoint(AhwAction.MOVE, 2f, 2f, 0.5f, 0f, 2L)
        assembler.onPoint(AhwAction.DOWN, 9f, 9f, 0.5f, 0f, 3L)
        val stroke = assembler.onPoint(AhwAction.UP, 10f, 10f, 0.5f, 0f, 4L)!!

        assertEquals(listOf(9f, 10f), stroke.map { it.x })
    }

    @Test
    fun sanitizesPressureTiltAndRejectsNonFiniteCoordinates() {
        val assembler = AhwStrokeAssembler()

        assertNull(assembler.onPoint(AhwAction.DOWN, Float.NaN, 1f, 0.5f, 0f, 1L))
        assertFalse(assembler.isStrokeActive)
        assembler.onPoint(AhwAction.DOWN, 1f, 1f, 3f, Float.POSITIVE_INFINITY, 1L)
        val stroke = assembler.onPoint(AhwAction.UP, 2f, 2f, Float.NaN, 0f, 2L)!!

        assertEquals(1f, stroke[0].pressure, 0f)
        assertEquals(0f, stroke[0].tilt, 0f)
        assertEquals(0f, stroke[1].pressure, 0f)
    }

    @Test
    fun assembledPointsCarryTheirButtons() {
        val assembler = AhwStrokeAssembler()

        assembler.onPoint(AhwAction.DOWN, 1f, 1f, 0.5f, 0f, 1L)
        val stroke = assembler.onPoint(AhwAction.UP, 2f, 2f, 0.5f, 0f, 2L, buttons = PenButtons.PRIMARY)!!

        assertEquals(listOf(PenButtons.NONE, PenButtons.PRIMARY), stroke.map { it.buttons })
    }

    @Test
    fun timelineAnswersTheButtonsHeldAtATimestamp() {
        val timeline = StylusButtonTimeline()

        assertEquals(PenButtons.NONE, timeline.buttonsAt(5L))
        timeline.record(10L, PenButtons.NONE)
        timeline.record(20L, PenButtons.PRIMARY)
        timeline.record(25L, PenButtons.PRIMARY) // unchanged: not a transition
        timeline.record(30L, PenButtons.NONE)

        assertEquals(PenButtons.NONE, timeline.buttonsAt(19L))
        assertEquals(PenButtons.PRIMARY, timeline.buttonsAt(20L))
        assertEquals(PenButtons.PRIMARY, timeline.buttonsAt(29L))
        assertEquals(PenButtons.NONE, timeline.buttonsAt(30L))
    }

    @Test
    fun timelineKeepsOnlyTheMostRecentTransitions() {
        val timeline = StylusButtonTimeline(capacity = 2)

        timeline.record(10L, PenButtons.PRIMARY)
        timeline.record(20L, PenButtons.NONE)
        timeline.record(30L, PenButtons.PRIMARY)

        assertEquals(PenButtons.NONE, timeline.buttonsAt(15L)) // forgotten
        assertEquals(PenButtons.NONE, timeline.buttonsAt(25L))
        assertEquals(PenButtons.PRIMARY, timeline.buttonsAt(35L))
    }

    @Test
    fun timelineIgnoresOutOfOrderEvents() {
        val timeline = StylusButtonTimeline()

        timeline.record(20L, PenButtons.PRIMARY)
        timeline.record(10L, PenButtons.NONE)

        assertEquals(PenButtons.PRIMARY, timeline.buttonsAt(25L))
    }

    @Test
    fun previewWidthIsLogicalWidthInPhysicalPixels() {
        assertEquals(10f, IReaderPenMapping.previewWidthPx(5f, 2f, pressure = 1f), 0f)
        assertEquals(0.5f, IReaderPenMapping.previewWidthPx(0f, 2f, pressure = 1f), 0f)
        assertEquals(128f, IReaderPenMapping.previewWidthPx(500f, 2f, pressure = 1f), 0f)
        assertEquals(5f, IReaderPenMapping.previewWidthPx(5f, 0f, pressure = 1f), 0f)
    }

    @Test
    fun previewMatchesTheHostRenderingAtTypicalPressure() {
        // OpenInkBridgeView draws width × density × pressure.
        val hostWidth = 5f * 2f * IReaderPenMapping.TYPICAL_PRESSURE

        assertEquals(hostWidth, IReaderPenMapping.previewWidthPx(5f, 2f), 0f)
        assertEquals(10f, IReaderPenMapping.previewWidthPx(5f, 2f, pressure = Float.NaN), 0f)
    }

    @Test
    fun previewColorSnapsToTheFirmwarePalette() {
        assertEquals(IReaderPenMapping.PEN_BLACK, IReaderPenMapping.previewColor(0xFF000000.toInt()))
        assertEquals(IReaderPenMapping.PEN_BLACK, IReaderPenMapping.previewColor(0xFF0000FF.toInt())) // blue
        assertEquals(IReaderPenMapping.PEN_BLACK, IReaderPenMapping.previewColor(0xFFFF0000.toInt())) // red
        // The sample web page's green, which inked nothing when passed through as-is.
        assertEquals(IReaderPenMapping.PEN_GRAY, IReaderPenMapping.previewColor(0xFF28A745.toInt()))
        assertEquals(IReaderPenMapping.PEN_WHITE, IReaderPenMapping.previewColor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun everyToolMapsToAFirmwarePen() {
        assertEquals("PEN_TYPE_GANGBI", IReaderPenMapping.penTypeConstant(StylusTool.PEN))
        assertEquals("PEN_TYPE_QIANBI", IReaderPenMapping.penTypeConstant(StylusTool.PENCIL))
        assertEquals("PEN_TYPE_MAOBI", IReaderPenMapping.penTypeConstant(StylusTool.BRUSH))
        StylusTool.values().forEach { assertTrue(IReaderPenMapping.penTypeConstant(it).startsWith("PEN_TYPE_")) }
    }

    @Test
    fun everyRefreshModeMapsToAFirmwareConstant() {
        val names = EInkRefreshMode.values().map(IReaderPenMapping::updateModeConstant)

        assertEquals(EInkRefreshMode.values().size, names.toSet().size)
        assertEquals("UI_EINK_DU_PART_MODE", IReaderPenMapping.updateModeConstant(EInkRefreshMode.SPEED))
        // UI_EINK_GC16_PART_MODE has the same value as UI_DEFAULT_MODE on device, so QUALITY must not use it.
        assertEquals("UI_EINK_GC16_FULL_MODE", IReaderPenMapping.updateModeConstant(EInkRefreshMode.QUALITY))
    }
}
