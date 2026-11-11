package org.openinkbridge.sdk

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PenButtonsTest {

    @Test
    fun mapsAndroidStylusButtons() {
        assertEquals(PenButtons.NONE, PenButtons.fromAndroidButtonState(0))
        assertEquals(PenButtons.PRIMARY, PenButtons.fromAndroidButtonState(MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertEquals(PenButtons.SECONDARY, PenButtons.fromAndroidButtonState(MotionEvent.BUTTON_STYLUS_SECONDARY))
        assertEquals(
            PenButtons.PRIMARY or PenButtons.SECONDARY,
            PenButtons.fromAndroidButtonState(MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)
        )
    }

    @Test
    fun mapsLegacyMouseStyleStylusButtons() {
        assertEquals(PenButtons.PRIMARY, PenButtons.fromAndroidButtonState(MotionEvent.BUTTON_SECONDARY))
        assertEquals(PenButtons.SECONDARY, PenButtons.fromAndroidButtonState(MotionEvent.BUTTON_TERTIARY))
        assertEquals(PenButtons.NONE, PenButtons.fromAndroidButtonState(MotionEvent.BUTTON_PRIMARY))
    }

    @Test
    fun smoothingKeepsEachPointsButtons() {
        val points = listOf(
            PenPoint(0f, 0f, 0.5f, 0f, 1L),
            PenPoint(1f, 1f, 0.5f, 0f, 2L, buttons = PenButtons.PRIMARY),
            PenPoint(2f, 2f, 0.5f, 0f, 3L)
        )

        assertEquals(listOf(0, PenButtons.PRIMARY, 0), StrokeSmoother.smooth(points).map { it.buttons })
    }
}
