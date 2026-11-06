// web/example/app.js

document.addEventListener("DOMContentLoaded", () => {
    const consoleEl = document.getElementById("debug-log");
    
    function log(msg) {
        const time = new Date().toTimeString().split(' ')[0];
        consoleEl.innerHTML += `[${time}] ${msg}<br>`;
        consoleEl.scrollTop = consoleEl.scrollHeight;
        console.log(msg);
    }

    // Capture global JS errors
    window.onerror = function(message, source, lineno, colno, error) {
        log(`<span style='color: #dc3545;'>ERR: ${message} at ${source}:${lineno}</span>`);
        return false;
    };

    log("Initializing canvas...");

    function detectEInkDevice() {
        const ua = navigator.userAgent.toLowerCase();
        if (ua.includes("boox") || ua.includes("onyx")) return "Onyx Boox Note/Tablet Series";
        if (ua.includes("bigme")) return "Bigme Tablet Series";
        if (ua.includes("remarkable")) return "reMarkable Paper Tablet";
        if (ua.includes("kobo")) return "Kobo E-Reader";
        return "Standard Screen (PC/Mobile)";
    }

    log(`Detected Hardware: <strong>${detectEInkDevice()}</strong>`);

    const OpenInkBridgeCanvasClass = window.OpenInkBridge?.OpenInkBridgeCanvas || window.OpenInkBridgeCanvas;
    if (!OpenInkBridgeCanvasClass) {
        log("<span style='color: #dc3545;'>Error: OpenInkBridge SDK failed to load.</span>");
        return;
    }

    const isNativeBridge = window.OpenInkBridge?.openInkBridge?.isSupported();
    if (isNativeBridge) {
        log("<span style='color: #28a745; font-weight: bold;'>⚡ RUNNING IN NATIVE CONTAINER (Near-Zero Latency Active!)</span>");
    } else {
        log("<span style='color: #ffc107; font-weight: bold;'>⚠️ RUNNING IN BROWSER FALLBACK MODE (Latency is unoptimized)</span>");
        log("<span style='color: #ffc107;'>Standard browsers block direct E-Ink hardware framebuffers. To experience zero-latency drawing, compile and deploy the Android ':app' sample onto your Boox Note!</span>");
    }

    // -------------------------------------------------------------
    // 1. Initialize Optimized Canvas (OpenInkBridge)
    // -------------------------------------------------------------
    const canvasOpt = document.getElementById("canvas-opt");
    const chkStylusOnly = document.getElementById("chk-stylus-only");
    
    const isCompatible = detectEInkDevice() !== "Standard Screen (PC/Mobile)";
    chkStylusOnly.checked = isCompatible;

    let currentStrokeWidth = 4;

    const osCanvas = new OpenInkBridgeCanvasClass(canvasOpt, {
        strokeColor: "#28a745", // Green
        strokeWidth: currentStrokeWidth,
        smoothing: true,
        stylusOnly: chkStylusOnly.checked
    });
    osCanvas.enableDrawing();
    log(`Optimized Canvas initialized (Stylus Only: ${chkStylusOnly.checked}).`);

    chkStylusOnly.addEventListener("change", () => {
        const isChecked = chkStylusOnly.checked;
        osCanvas.setStyle("#28a745", currentStrokeWidth, isChecked);
        log(`Stylus Only Mode toggled: ${isChecked ? "ENABLED (palm rejection active)" : "DISABLED (touch drawing active)"}`);
    });

    // Expose a helper to update stroke width from Android WebView host
    window.setStrokeWidth = function(width) {
        currentStrokeWidth = width;
        if (osCanvas) {
            osCanvas.setStyle("#28a745", width, chkStylusOnly.checked);
        }
    };

    // -------------------------------------------------------------
    // 2. Clear Button
    // -------------------------------------------------------------
    const btnClear = document.getElementById("btn-clear");
    btnClear.addEventListener("click", () => {
        osCanvas.clear();
        log("Canvas cleared.");
    });

    // Monitor optimized stroke completions
    osCanvas.onStrokeFinished((strokePoints) => {
        log(`Optimized: stroke completed. Points count: ${strokePoints.length}`);
    });
});
