import { beforeEach, describe, expect, it } from "vitest";
import { loadPrefs, savePrefs } from "./workbenchPrefs";

describe("workbenchPrefs", () => {
    beforeEach(() => localStorage.clear());

    it("returns defaults and merges saved values", () => {
        expect(loadPrefs()).toEqual({ applyLayout: "place", focus: false, panes: { left: 260, right: 380 },
            leftCollapsed: false, rightCollapsed: false, testStripCollapsed: true, bottomPanelTab: "tests" });
        savePrefs({ applyLayout: "tidy", panes: { left: 5000, right: 10 } });
        expect(loadPrefs().applyLayout).toBe("tidy");
        expect(loadPrefs().panes).toEqual({ left: 800, right: 160 });
    });

    it("always opens the bottom panel collapsed", () => {
        savePrefs({ testStripCollapsed: false });
        // The bottom panel always opens collapsed, whatever was saved.
        expect(loadPrefs().testStripCollapsed).toBe(true);
    });

    it("remembers the bottom panel tab", () => {
        savePrefs({ bottomPanelTab: "runs" });
        expect(loadPrefs().bottomPanelTab).toBe("runs");
        localStorage.setItem("axiom.workbench.prefs", JSON.stringify({ bottomPanelTab: "x" }));
        expect(loadPrefs().bottomPanelTab).toBe("tests");
    });

    it("ignores corrupt storage", () => {
        localStorage.setItem("axiom.workbench.prefs", "{nope");
        expect(loadPrefs().focus).toBe(false);
    });
});
