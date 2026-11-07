# Musnap Integration Guide

Musnap e-ink tablets (e.g. the Musnap Ocean) run iReader / ZhangYue firmware on Android 14. The
firmware adds an `android.eink` package to the device framework with a system pen fast path —
"auto handwriting" — that inks the stylus straight to the EPDC while the pen moves and reports the
points back to the app. `MusnapEpdAdapter` drives it. It needs no dependency: everything is
resolved by reflection, and the probe fails fast on any other device.

## Status

Implemented and tested on a Musnap Ocean (firmware 3.1.10.187.0, MT8781, Android 14). Every
firmware call the adapter makes was checked from a regular debug-signed app targeting API 34 with no
hidden-API exemption — the sample app's exemption is not needed for this backend.

## How it works

The fast path only exists on the firmware's own `android.eink.view.HWSurfaceView`. The call that
would bind it to an arbitrary `SurfaceView` (`SurfaceControlAPI.bindFBSurface`) is on the hidden-API
blocklist for third-party apps, and a standalone `FBHandSurface` receives no pen input. So the
adapter implements the hybrid overlay of OIP §1.3 itself:

1. It adds a transparent, `setZOrderOnTop` `HWSurfaceView` to the window's content frame, kept over
   the host's drawing region on every frame (host view, WebView canvas overlay, or drawing limit).
2. The firmware inks the stylus inside that region and reports each point — position, pressure,
   tilt and event time — through `IAhwTouchListenerV2` (V1, without tilt, as a fallback).
3. On pen-up the stroke goes to the host. `OpenInkBridgeView` commits the raw points and renders
   them with the same firmware pen (see below); a WebView receives `strokeFinished`.
4. Once the host has committed a frame containing the stroke (`strokeDrawn` for WebViews), the
   overlay is cleared. Clearing earlier makes the stroke blink out.

The firmware's ink lives only in the overlay's surface, which is recreated whenever the app returns
to the foreground — the host owns every committed stroke, so nothing is lost.

Fingers are never inked by the firmware. They fall through the overlay to the host, which draws
them on its software path when `stylusOnly` is off.

Inking is switched off while the window does not have focus, meant to keep dialogs and menus
drawn over the canvas from being inked through (not yet verified on device).

Do not toggle `setRawDrawingEnabled` on every `ACTION_DOWN`, as Onyx hosts do. The firmware starts
inking before the app sees a touch, so a stroke that re-enables inking on its own `ACTION_DOWN`
gets no live ink. Musnap does not need the toggle — it only inks inside the overlay — and reports
`rawDrawingTouchHandoff = false`; `OpenInkBridgeView.needsRawDrawingTouchHandoff` tells a host
whether to toggle. WebView hosts are handled inside the adapter: there, inking follows whether the
page's canvas overlay is visible.

## Edge gestures: name the drawing activity `…ActivityNote`

The firmware turns swipes from the screen edges into system actions — swiping in from the left is
*back* — and by default it does so for the stylus too. A stroke that starts close to an edge is
therefore taken by the gesture and can close the app mid-drawing.

There is no API to opt out. The firmware (`android.ireader.IreaderEdgeGesture.isHandWriteScence`)
disarms edge gestures for the stylus only while the focused activity's class name contains
`ActivityNote`, `ActivityRead` or `ImageGalleryActivity` — the names of its own apps. Fingers keep
their gestures either way. Apps that draw near the screen edges should name their drawing activity
accordingly, e.g. `SketchActivityNote`; the adapter logs `EDGE_GESTURES_ARMED` when the host
activity does not qualify. Verified on device: in an activity named this way, the firmware logs
`handwriteScene is true … do not trigger gesture` for pen strokes starting at the screen edges, and
the app stays in front.

## Pens: the committed stroke is the preview

The live ink and the committed stroke are drawn by the same engine. The firmware's `HandPen` renders
into any software-backed `Canvas`, so `MusnapEpdAdapter.renderStroke` draws committed strokes with
it (`vendorStrokeRendering` capability), and `OpenInkBridgeView` keeps them in a bitmap layer built
that way. The view commits the raw points: smoothing them would move the stroke at pen-up.

`OpenInkBridgeView.setStylusTool` picks the pen for both:

| `StylusTool` | Firmware pen (`HandPenConfig`) |
| --- | --- |
| `PEN` (default) | `PEN_TYPE_GANGBI` — fountain pen, pressure-sensitive |
| `PENCIL` | `PEN_TYPE_QIANBI` — textured pencil |
| `BRUSH` | `PEN_TYPE_MAOBI` — brush |
| `HIGHLIGHTER`, erasers | `PEN_TYPE_SIMPLE` — constant width |

Widths are in physical pixels, so the adapter passes `width × density`.

WebView pages render committed strokes with their own JS renderer, which the firmware pen cannot
reach. Their preview is the constant-width `PEN_TYPE_SIMPLE` at `width × density × 0.5` — the
pressure this pen reports for ordinary writing, since the web canvas draws `width × pressure` — and a
stroke still changes slightly at pen-up: thickness follows the pressure along the stroke, and the
page's anti-aliased stroke is shown with a different waveform than the 1-bit firmware ink.

The fast path only has black, grey and white pens (`HandPenConfig.PEN_COLOR_*`); a mid-tone colour
passed through as-is inks nothing visible. The preview takes the nearest of the three by luminance,
and committed strokes keep their real colour.

A pen surface keeps the geometry it was first bound with — resized, it still reports points but
inks nothing — so the adapter binds a new overlay whenever the drawing region changes size (a
WebView enabling writing mode on a canvas, a layout change), and only moves it otherwise.

## Refresh modes

| OIP refresh mode | Firmware constant (`android.eink.api.EinkMode`) |
| --- | --- |
| `SPEED` | `UI_EINK_DU_PART_MODE` |
| `BALANCED` | `UI_DEFAULT_MODE` (a partial GC16 update) |
| `QUALITY` | `UI_EINK_GC16_FULL_MODE` |

`triggerFullRefresh()` calls `EinkAPI.applyFullScreenRefresh`. Constants are read by name at
runtime, so their values always come from the device.

## Measured behaviour

Measured on a Musnap Ocean with `OpenInkBridgeView` (full screen, `stylusOnly = true`):

* **Overlays.** A transparent view above the canvas that ignores touches (grid lines, labels,
  marks) does not disturb live ink. Invalidating part of it while the user writes the next stroke
  loses no ink and leaves no ghosting.
* **Dialogs.** Nothing inks through an `AlertDialog` drawn on with the pen. The first stroke after
  closing it gets live ink again.
* **Finger.** With `stylusOnly`, finger touches produce no ink and still reach the app.
* **Hover.** Stylus hover events carry a position; `AXIS_DISTANCE` is always 0. Hover exit arrives a
  few milliseconds before the stroke's `ACTION_DOWN`.
* **Pressure.** The median pressure of a stroke is about 0.35–0.50 when writing lightly and
  0.6–0.85 when writing normally. Every stroke starts lower (0.01–0.3) at pen-down, so classify on
  the median, not the minimum.
* **Redrawing the canvas.** `clear()` and re-adding strokes costs about 1 ms per stroke (50 strokes
  ≈ 55 ms, 150 ≈ 120 ms to the first frame) with no visible flash.

## Firmware API used

| Purpose | API |
| --- | --- |
| Overlay surface | `android.eink.view.HWSurfaceView(Context)`, `getFBSurface()` |
| Pen fast path | `IFBHandSurface.setAhwToolTypes`, `setAhwPen`, `setAutoHandWrite` |
| Stroke points | `IFBHandSurface.setAhwTouchListenerV2` / `setAhwTouchListener` |
| Committed strokes | `android.eink.view.HandPen(int, boolean)`, `setCanvasBoard`, `onTouchEventV2(HandPoint)`, `draw` |
| Refresh | `android.eink.api.ViewAPI.setUpdateMode`, `EinkAPI.applyFullScreenRefresh` |

The same firmware may ship on other iReader-based devices. The adapter selects itself wherever the
API probes successfully, regardless of brand: Musnap devices report rebranded build properties.

## Limitations

* The gesture strips are wide: on a Musnap Ocean (1264×1680) they are 136px along the left and
  right edges and across the whole bottom edge. On Musnap, run the sample through its
  "OpenInkBridge Sample (Musnap)" launcher entry (`MusnapSampleActivityNote`); in the regular
  entry, strokes in those strips trigger system gestures.
* WebView strokes change slightly at pen-up (see "Pens").
* An Android process that crashes or is killed repeatedly gets flagged "bad", after which the
  system refuses to start the WebView's renderer for it and WebView content stays blank. Starting
  the app from the launcher clears the flag; restarts from `adb` did not.
* The firmware pen is restricted to `TOOL_TYPE_STYLUS`; the eraser end should therefore reach the
  host as ordinary touch input. Not tested.
