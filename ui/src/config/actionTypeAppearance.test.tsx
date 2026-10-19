import { describe, expect, it } from "vitest";
import { isValidElement } from "react";
import { actionTypeAppearance, AGENT_ACTION_COLOR, SDK_ACTION_COLOR } from "./actionTypeAppearance";

describe("actionTypeAppearance", () => {
    it("gives SDK calls the SDK icon and color", () => {
        const appearance = actionTypeAppearance("sdk:axiom_create_task", undefined);
        expect(isValidElement(appearance.icon)).toBe(true);
        expect(appearance.color).toBe(SDK_ACTION_COLOR);
    });

    it("gives agent action types the agent icon and color", () => {
        const appearance = actionTypeAppearance("summarize", "agent");
        expect(isValidElement(appearance.icon)).toBe(true);
        expect(appearance.color).toBe(AGENT_ACTION_COLOR);
    });

    it("uses different icons for SDK and agent actions", () => {
        const sdk = actionTypeAppearance("sdk:x", undefined).icon as { type: unknown };
        const agent = actionTypeAppearance("y", "agent").icon as { type: unknown };
        expect(sdk.type).not.toBe(agent.type);
    });

    it("leaves script, node and unknown action types with the default look", () => {
        expect(actionTypeAppearance("run-script", "script")).toEqual({});
        expect(actionTypeAppearance("run-node", "node")).toEqual({});
        expect(actionTypeAppearance("mystery", undefined)).toEqual({});
    });
});
