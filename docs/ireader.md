# iReader Integration Guide

E-ink tablets on iReader / ZhangYue firmware run Android 14: the Musnap Ocean, and rebranded MT8781
tablets that report other brands in their build properties (e.g. Byybuo / Chitech TAB-Executive).
The firmware adds an `android.eink` package to the device framework with a system pen fast path —
"auto handwriting" — that inks the stylus straight to the EPDC while the pen moves and reports the
points back to the app. `IReaderEpdAdapter` drives it. It needs no dependency: everything is
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
gets no live ink. This firmware does not need the toggle — it only inks inside the overlay — and reports
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
into any software-backed `Canvas`, so `IReaderEpdAdapter.renderStroke` draws committed strokes with
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

## Stylus button

The pen's barrel button is a standard Android stylus button (`BTN_STYLUS` →
`BUTTON_STYLUS_PRIMARY`), and the firmware ignores it: it keeps inking while the button is held.
The firmware's own points carry no buttons, so the adapter records the button state of the
`MotionEvent`s delivered alongside them and matches each point by timestamp.

* The button is reported only while the tip touches the screen, never while hovering.
* Pressing it mid-stroke makes the driver lift and touch down again within a few milliseconds, so
  the stroke splits in two at the press; the second part carries the button. Releasing it does not
  split the stroke.
* `setInkingPaused(true)` turns the fast path off (`setAutoHandWrite(false)`) and clears what it
  inked. The firmware starts inking before the app sees the button, so a stroke started with the
  button held shows its ink for about half a second before it is cleared. Points lag the input by a couple of
  hundred milliseconds; those stamped before the pause still complete and commit the stroke the
  firmware was inking.

Hover is reported (`ACTION_HOVER_*`), with no distance.

### Erasing with the button

Erasing from the app's own view is slow on this panel: every redraw waits for an ordinary view
update, and redrawing while the user moves holds up the next one. With an eraser selected
(`StylusTool.ERASER_POINT`, `ERASER_STROKE`), `OpenInkBridgeView` erases on the panel itself instead,
through the adapter's `eraseOnPanel` and the firmware's `HWEraser` in its quick style: it paints the
eraser background (white without one) under a circle of the eraser's radius along the pen, as fast
as the ink. The view applies the erase to its strokes at pen-up; the redraw then shows the real
content.

* The background is the view's size and holds what lies under the strokes (grid lines, a page),
  drawn when the erase starts.
* Resuming the ink redraws the view. Resumed before the strokes drop the erased ink, the erased
  strokes flash back for a moment, so the view resumes it only once it has applied the erase, even
  if the eraser was deselected first.
* The eraser never gets the stroke's UP: it would repaint the panel from the eraser's view, which
  flashes the erased ink back the same way.
* Its pictures need an `HWEraserView`; one that is created but never shown does. Its default track
  style draws into that view, so nothing shows until the redraw.
* Building an `HWEraser` takes about 200 ms: the firmware calls a native method it lacks
  (`FBPaint.nativeSetDitherMode`) and logs the error. The adapter builds its eraser once, when the
  overlay is ready, and only rebinds the background per erase; built at the first erase, it made
  the panel blink and left the start unerased.

Measured on the Musnap Ocean: the erase starts about 30 ms after the touch, and each call takes
about 5 ms. Feeding it every historical point (about 50 per event) took over 500 ms per event and
fell behind the pen, so the adapter sends the newest point and two more of each batch. The panel
therefore erases chords of a quick curve, so the view cuts the strokes along `panelErasePath`, the
points it actually went through, not the stroke's own points.

With the button, the firmware has already started inking when the app learns of it (the button
is reported only once the tip touches). Pausing it cancels that stroke a few milliseconds of input
in, and the starting circle may show bits of the erased strokes until pen-up, when the view
redraws. Erasing that circle again (whole start, start circle alone, the reported late points, at
50–500 ms), drawing an outline as a cursor and pausing by refusing the stylus instead of turning the
fast path off were all tried on the Musnap Ocean: each extra pass overlaps the live erase's panel
updates and glitches more than it fixes. An eraser selected before the pen touches has none of this.

* No cursor. `setShowBorder(true)` draws none in the quick style. `HandEraser`, the eraser the
  notes app uses, has a circle style that blocks the caller for seconds; its only usable style
  paints plain white, and its static `setStyle` persists system-wide (`persist.eraser.style`, `1` by
  default), so the adapter does not use it.
* Re-rendering the cut strokes does not match the erase: the panel erases every pixel inside the
  circle, a cut stroke ends on a point and the pen draws its end anew. The view clears the same
  circles from its stroke layer instead; the cut strokes show as rendered only when that area is
  next redrawn. Each cut end is placed where the stroke crosses the eraser's edge, a quarter of
  the stroke's half-width outside it, which matched the panel best.
* Cutting a long scribble took a second checking every eraser point against every stroke point;
  bucketing the eraser path by position brought it to about 150 ms.

## Excluded regions

`OpenInkBridgeView.setExcludedRegions` keeps the firmware's ink out of areas that take taps rather
than writing (e.g. crossword clue cells):

* The adapter clips the pen surface to the overlay minus the regions (`IFBHandSurface.setClip`),
  set again whenever the overlay moves or is bound anew. The firmware then inks nothing there: a
  stroke crossing a region stops at its edge and resumes past it.
* A stroke that starts in a region is not taken by the overlay: it falls through to the views
  under it as ordinary stylus touch events, so a tap reaches the app. The firmware reports no
  points for it; any it did report would be dropped.
* Committed strokes are cut at the regions like on every backend (see ANDROID_INTEGRATION.md), so
  they match the clipped ink.

Verified on an iReader-firmware tablet (MT8781, Android 14) with a crossword grid: no ink in the
excluded cells, taps on them reported to the app, strokes crossing them inked on both sides.
Turning the fast path off while the pen hovers over a region (`disableAutoHandWrite`) also works,
but the first hover point after the pen comes into range is reported at the screen's bottom-left
corner, and the clip needs no timing.

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
  marks) does not disturb live ink and leaves no ghosting, but see "Keep the screen still while
  the user writes" below.
* **Dialogs.** Nothing inks through an `AlertDialog` drawn on with the pen. The first stroke after
  closing it gets live ink again.
* **Finger.** With `stylusOnly`, finger touches produce no ink and still reach the app.
* **Hover.** Stylus hover events carry a position; `AXIS_DISTANCE` is always 0. Hover exit arrives a
  few milliseconds before the stroke's `ACTION_DOWN`.
* **Pressure.** The median pressure of a stroke is about 0.35–0.50 when writing lightly and
  0.6–0.85 when writing normally. Every stroke starts lower (0.01–0.3) at pen-down, so classify on
  the median, not the minimum.
* **Redrawing the canvas.** Replacing the strokes costs about 1 ms per stroke (50 strokes ≈ 55 ms,
  150 ≈ 120 ms to the first frame) with no visible flash. Assigned strokes go through the firmware
  pen like live ones, so pencil strokes still look like pencil.

## Keep the screen still while the user writes

The firmware draws its ink inside the system compositor. Any ordinary view update the app makes
while the user writes (a status line, a mark, the stroke it just committed) makes the compositor
redo its e-ink work, and a stroke that starts meanwhile gets its first ink 150–250 ms late instead
of 15–40 ms. The firmware's notes app avoids this by changing nothing on screen while the pen is
in use. What did not help: the speed refresh mode, and `EPDCDevice.setDelayRefreshInterval`.

* `OpenInkBridgeView` therefore keeps each finished stroke as firmware ink and draws it into the
  view only once the pen is idle. The stroke is reported to the stroke listener, and is in
  `strokes`, at once.
* Apps should queue their own cosmetic updates with `runWhenPenIdle { … }`, which runs them after
  `penIdleDelayMs` (2 s) without a stroke, or as soon as the pen leaves hover range.

Measured over about twelve letters each: with both, no stroke started more than 100 ms late;
committing each stroke at once made a third of them late, updating a mark straight away one in
twelve.

## Firmware API used

| Purpose | API |
| --- | --- |
| Overlay surface | `android.eink.view.HWSurfaceView(Context)`, `getFBSurface()` |
| Pen fast path | `IFBHandSurface.setAhwToolTypes`, `setAhwPen`, `setAutoHandWrite` |
| Excluded regions | `IFBHandSurface.setClip(Path)` |
| Stroke points | `IFBHandSurface.setAhwTouchListenerV2` / `setAhwTouchListener` |
| Committed strokes | `android.eink.view.HandPen(int, boolean)`, `setCanvasBoard`, `onTouchEventV2(HandPoint)`, `draw` |
| Panel eraser | `android.eink.view.HWEraser(IFBHandSurface, SurfacePicture, FBPicture)`, `setRadius`, `setStyle(STYLE_QUICK)`, `onTouchEvent(int, int, int)`; `HWEraserView.SurfacePicture.bindColor`, `HWEraserView.FBPicture.bindBitmap`/`bindColor`, `setViewLocation` |
| Refresh | `android.eink.api.ViewAPI.setUpdateMode`, `EinkAPI.applyFullScreenRefresh` |

The adapter selects itself wherever the firmware API probes successfully, regardless of brand:
devices on this firmware report rebranded build properties (`ro.fota.oem` names the ZhangYue
platform, e.g. `zhangyue_MTK8781_14.0`).

## Limitations

* The gesture strips are wide: on a Musnap Ocean (1264×1680) they are 136px along the left and
  right edges and across the whole bottom edge. On these devices, run the sample through its
  "OpenInkBridge Sample (iReader)" launcher entry (`IReaderSampleActivityNote`); in the regular
  entry, strokes in those strips trigger system gestures.
* WebView strokes change slightly at pen-up (see "Pens").
* An Android process that crashes or is killed repeatedly gets flagged "bad", after which the
  system refuses to start the WebView's renderer for it and WebView content stays blank. Starting
  the app from the launcher clears the flag; restarts from `adb` did not.
* The firmware pen is restricted to `TOOL_TYPE_STYLUS`; the eraser end should therefore reach the
  host as ordinary touch input. Not tested: the Musnap Ocean's pen has no eraser end.
* `setInkingPaused` and the live button listener are native-view only; WebView pages get the
  button state on the points of finished strokes.
