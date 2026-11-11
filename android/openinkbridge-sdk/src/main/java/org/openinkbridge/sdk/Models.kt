package org.openinkbridge.sdk

enum class StylusTool {
    PEN, PENCIL, BRUSH, HIGHLIGHTER, ERASER_STROKE, ERASER_POINT
}

data class PenPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float,
    val timestamp: Long,
    /** Barrel buttons held at this point, as a [PenButtons] bitmask. */
    val buttons: Int = PenButtons.NONE
)

/**
 * Stylus barrel-button bitmask carried by [PenPoint.buttons] (OIP §3.3). It is the same on every
 * backend and in the web bridge, whatever the platform reports.
 */
object PenButtons {
    const val NONE = 0
    const val PRIMARY = 1
    const val SECONDARY = 2

    // MotionEvent.BUTTON_* values; older stylus drivers report the mouse-style SECONDARY/TERTIARY.
    private const val ANDROID_SECONDARY = 0x02
    private const val ANDROID_TERTIARY = 0x04
    private const val ANDROID_STYLUS_PRIMARY = 0x20
    private const val ANDROID_STYLUS_SECONDARY = 0x40

    /** Maps a stylus `MotionEvent.getButtonState()` to the OIP bitmask. */
    fun fromAndroidButtonState(buttonState: Int): Int {
        var buttons = NONE
        if (buttonState and (ANDROID_STYLUS_PRIMARY or ANDROID_SECONDARY) != 0) buttons = buttons or PRIMARY
        if (buttonState and (ANDROID_STYLUS_SECONDARY or ANDROID_TERTIARY) != 0) buttons = buttons or SECONDARY
        return buttons
    }
}

enum class EInkRefreshMode {
    SPEED,      // Fast, low quality (high ghosting) - ideal for quick sketching
    QUALITY,    // High quality, slow - ideal for viewing text/details
    BALANCED    // Middle ground
}

data class DrawingStroke(
    val points: List<PenPoint>,
    val color: Int,
    val width: Float,
    val tool: StylusTool = StylusTool.PEN
)
