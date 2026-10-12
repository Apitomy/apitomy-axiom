import { beforeEach, describe, expect, it } from "vitest";
import { loadPrefs, savePrefs } from "./workbenchPrefs";

describe("workbenchPrefs", () => {
    beforeEach(() => localStorage.clear());

    it("returns defaults and merges saved values", () => {
        expect(loadPrefs()).toEqual({ applyLayout: "place", focus: false, panes: { left: 260, right: 380 },
            leftCollapsed: false, rightCollapsed: false });
        savePrefs({ applyLayout: "tidy", panes: { left: 5000, right: 10 } });
        expect(loadPrefs().applyLayout).toBe("tidy");
        expect(loadPrefs().panes).toEqual({ left: 800, right: 160 });
    });

    it("ignores corrupt storage", () => {
        localStorage.setItem("axiom.workbench.prefs", "{nope");
        expect(loadPrefs().focus).toBe(false);
    });
});
