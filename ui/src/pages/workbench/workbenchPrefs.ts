/** Widths of the workbench side panes, in pixels. */
export type PaneSizes = { left: number; right: number };

/** Persisted workbench preferences. */
export interface WorkbenchPrefs {
    applyLayout: "place" | "tidy";
    focus: boolean;
    panes: PaneSizes;
    leftCollapsed: boolean;
    rightCollapsed: boolean;
    /** Whether the Test output strip under the centre pane is collapsed. */
    testStripCollapsed: boolean;
    /** The active tab of the bottom panel. */
    bottomPanelTab: BottomPanelTab;
}

/** Tabs of the bottom panel under the centre pane. */
export type BottomPanelTab = "tests" | "runs";

const KEY: string = "axiom.workbench.prefs";
const MIN_PANE: number = 160;
const MAX_PANE: number = 800;

function defaults(): WorkbenchPrefs {
    return { applyLayout: "place", focus: false, panes: { left: 260, right: 380 }, leftCollapsed: false,
        rightCollapsed: false, testStripCollapsed: true, bottomPanelTab: "tests" };
}

function pane(value: unknown, fallback: number): number {
    if (typeof value !== "number" || !Number.isFinite(value)) {
        return fallback;
    }
    return Math.min(MAX_PANE, Math.max(MIN_PANE, value));
}

function readRaw(): Record<string, unknown> {
    try {
        const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? "{}");
        return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {};
    } catch {
        return {};
    }
}

/**
 * Loads workbench preferences, falling back to defaults for missing or invalid values.
 * @returns the preferences
 */
export function loadPrefs(): WorkbenchPrefs {
    const d: WorkbenchPrefs = defaults();
    const raw: Record<string, unknown> = readRaw();
    const panes: Record<string, unknown> = raw.panes && typeof raw.panes === "object"
        ? raw.panes as Record<string, unknown> : {};
    return {
        applyLayout: raw.applyLayout === "tidy" || raw.applyLayout === "place" ? raw.applyLayout : d.applyLayout,
        focus: typeof raw.focus === "boolean" ? raw.focus : d.focus,
        panes: { left: pane(panes.left, d.panes.left), right: pane(panes.right, d.panes.right) },
        leftCollapsed: typeof raw.leftCollapsed === "boolean" ? raw.leftCollapsed : d.leftCollapsed,
        rightCollapsed: typeof raw.rightCollapsed === "boolean" ? raw.rightCollapsed : d.rightCollapsed,
        testStripCollapsed: typeof raw.testStripCollapsed === "boolean" ? raw.testStripCollapsed : d.testStripCollapsed,
        bottomPanelTab: raw.bottomPanelTab === "tests" || raw.bottomPanelTab === "runs" ? raw.bottomPanelTab : d.bottomPanelTab,
    };
}

/**
 * Merges the given values into the stored preferences.
 * @param partial the values to save
 */
export function savePrefs(partial: Partial<WorkbenchPrefs>): void {
    const merged: WorkbenchPrefs = { ...loadPrefs(), ...partial };
    try {
        localStorage.setItem(KEY, JSON.stringify(merged));
    } catch {
        // storage unavailable; ignore
    }
}
