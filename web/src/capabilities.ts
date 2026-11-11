/**
 * Hardware capabilities (OIP §7.1, schema version 2): what the device is and what the native
 * backend does with it. Values that cannot be established are null.
 */
export const HARDWARE_CAPABILITIES_SCHEMA_VERSION = 2 as const;

export type ValueSource = 'vendor' | 'deviceTable' | 'heuristic' | 'none';

/** A value that may be inferred rather than reported, with where it came from; null if unknown. */
export interface Sourced<T> {
    value: T | null;
    source: ValueSource;
}

export type Runtime = 'android' | 'linux' | 'web';
export type DisplayTechnology = 'eink' | 'conventional';
export type PanelColor = 'monochrome' | 'color';
export type RefreshMode = 'SPEED' | 'BALANCED' | 'QUALITY';

export type LiveInkColors =
    | { kind: 'full' }
    | { kind: 'greyscale' }
    | { kind: 'fixed'; values: string[] };

export interface HardwareCapabilities {
    schemaVersion: typeof HARDWARE_CAPABILITIES_SCHEMA_VERSION;
    device: {
        manufacturer: string | null;
        brand: string | null;
        model: string | null;
        runtime: Runtime;
        firmware: Sourced<string>;
    };
    display: {
        technology: Sourced<DisplayTechnology>;
        color: Sourced<PanelColor>;
        panelModel: string | null;
        widthPx: number | null;
        heightPx: number | null;
        ppi: number | null;
    };
    digitizer: {
        stylus: boolean | null;
        pressure: boolean | null;
        tilt: boolean | null;
        hover: boolean | null;
        eraser: boolean | null;
    };
    /** The native backend, e.g. "onyx"; null without one. */
    backend: string | null;
    epdc: {
        refreshModes: RefreshMode[];
        fullRefresh: boolean;
    };
    /** The native low-latency ink path; null without one. */
    liveInk: { colors: LiveInkColors | null } | null;
    /** Vendor-specific values keyed `<vendor>.<name>`; they may change between releases. */
    extras: Record<string, unknown>;
}

const VALUE_SOURCES: readonly ValueSource[] = ['vendor', 'deviceTable', 'heuristic', 'none'];
const RUNTIMES: readonly Runtime[] = ['android', 'linux', 'web'];
const TECHNOLOGIES: readonly DisplayTechnology[] = ['eink', 'conventional'];
const PANEL_COLORS: readonly PanelColor[] = ['monochrome', 'color'];
const REFRESH_MODES: readonly RefreshMode[] = ['SPEED', 'BALANCED', 'QUALITY'];
const HEX_COLOR = /^#[0-9a-f]{6}$/i;
const MAX_STRING_LENGTH = 256;
const MAX_LIST_LENGTH = 64;

/**
 * Validates an `OipHardwareCapabilities` document from an untrusted source (the native bridge).
 * Unknown fields are dropped and malformed values become null; a document of another schema
 * version, or one that is not an object, is rejected.
 */
export function parseHardwareCapabilities(input: unknown): HardwareCapabilities | null {
    const value = typeof input === 'string' ? safeJson(input) : input;
    if (!isRecord(value) || value.schemaVersion !== HARDWARE_CAPABILITIES_SCHEMA_VERSION) return null;

    const device = record(value.device);
    const display = record(value.display);
    const digitizer = record(value.digitizer);
    const epdc = record(value.epdc);
    const runtime = oneOf(device.runtime, RUNTIMES);
    if (!runtime) return null;

    return {
        schemaVersion: HARDWARE_CAPABILITIES_SCHEMA_VERSION,
        device: {
            manufacturer: text(device.manufacturer),
            brand: text(device.brand),
            model: text(device.model),
            runtime,
            firmware: sourced(device.firmware, text)
        },
        display: {
            technology: sourced(display.technology, (v) => oneOf(v, TECHNOLOGIES)),
            color: sourced(display.color, (v) => oneOf(v, PANEL_COLORS)),
            panelModel: text(display.panelModel),
            widthPx: positiveInteger(display.widthPx),
            heightPx: positiveInteger(display.heightPx),
            ppi: positiveNumber(display.ppi)
        },
        digitizer: {
            stylus: flag(digitizer.stylus),
            pressure: flag(digitizer.pressure),
            tilt: flag(digitizer.tilt),
            hover: flag(digitizer.hover),
            eraser: flag(digitizer.eraser)
        },
        backend: text(value.backend),
        epdc: {
            refreshModes: list(epdc.refreshModes).map((mode) => oneOf(mode, REFRESH_MODES)).filter(isPresent),
            fullRefresh: epdc.fullRefresh === true
        },
        liveInk: isRecord(value.liveInk) ? { colors: liveInkColors(value.liveInk.colors) } : null,
        extras: extras(value.extras)
    };
}

/**
 * What a page can tell without a native bridge: little. E-ink browsers rarely report the
 * `update` and `monochrome` media features faithfully, so a match counts as a heuristic and
 * anything else as unknown.
 */
export function detectBrowserCapabilities(): HardwareCapabilities {
    const capabilities = unknownCapabilities('web');
    if (mediaMatches('(update: slow)')) capabilities.display.technology = { value: 'eink', source: 'heuristic' };
    if (mediaMatches('(monochrome)')) capabilities.display.color = { value: 'monochrome', source: 'heuristic' };
    return capabilities;
}

export function unknownCapabilities(runtime: Runtime): HardwareCapabilities {
    return {
        schemaVersion: HARDWARE_CAPABILITIES_SCHEMA_VERSION,
        device: { manufacturer: null, brand: null, model: null, runtime, firmware: unknown() },
        display: { technology: unknown(), color: unknown(), panelModel: null, widthPx: null, heightPx: null, ppi: null },
        digitizer: { stylus: null, pressure: null, tilt: null, hover: null, eraser: null },
        backend: null,
        epdc: { refreshModes: [], fullRefresh: false },
        liveInk: null,
        extras: {}
    };
}

function unknown<T>(): Sourced<T> {
    return { value: null, source: 'none' };
}

function mediaMatches(query: string): boolean {
    try {
        return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
    } catch {
        return false;
    }
}

function liveInkColors(value: unknown): LiveInkColors | null {
    if (!isRecord(value)) return null;
    switch (value.kind) {
        case 'full':
            return { kind: 'full' };
        case 'greyscale':
            return { kind: 'greyscale' };
        case 'fixed': {
            const values = list(value.values).filter((color): color is string => typeof color === 'string' && HEX_COLOR.test(color));
            return values.length > 0 ? { kind: 'fixed', values } : null;
        }
        default:
            return null;
    }
}

function sourced<T>(input: unknown, read: (value: unknown) => T | null): Sourced<T> {
    if (!isRecord(input)) return unknown();
    const value = read(input.value);
    const source = oneOf(input.source, VALUE_SOURCES);
    return value === null || !source || source === 'none' ? unknown() : { value, source };
}

function extras(input: unknown): Record<string, unknown> {
    if (!isRecord(input)) return {};
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(input).slice(0, MAX_LIST_LENGTH)) {
        if (!/^[a-z0-9-]+\.[A-Za-z0-9._-]{1,64}$/.test(key)) continue;
        if (typeof value === 'string') {
            if (value.length <= MAX_STRING_LENGTH) result[key] = value;
        } else if (typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value)) || value === null) {
            result[key] = value;
        }
    }
    return result;
}

function text(value: unknown): string | null {
    return typeof value === 'string' && value.length > 0 && value.length <= MAX_STRING_LENGTH ? value : null;
}

function flag(value: unknown): boolean | null {
    return typeof value === 'boolean' ? value : null;
}

function positiveInteger(value: unknown): number | null {
    return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : null;
}

function positiveNumber(value: unknown): number | null {
    return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | null {
    return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? value as T : null;
}

function list(value: unknown): unknown[] {
    return Array.isArray(value) ? value.slice(0, MAX_LIST_LENGTH) : [];
}

function record(value: unknown): Record<string, unknown> {
    return isRecord(value) ? value : {};
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isPresent<T>(value: T | null): value is T {
    return value !== null;
}

function safeJson(text: string): unknown {
    if (text.length > 1_000_000) return null;
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}
