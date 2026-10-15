import { describe, expect, it } from "vitest";
import { isPickableTemplate } from "./CreateSessionModal";

describe("isPickableTemplate", () => {
    it("hides the Workflow Workbench template (composition capability) from the AI Assistant picker", () => {
        expect(isPickableTemplate({ templateId: "axiom-workbench", capabilities: ["composition"] } as never)).toBe(false);
        expect(isPickableTemplate({ templateId: "general-assistant", capabilities: [] } as never)).toBe(true);
        expect(isPickableTemplate({ templateId: "custom" } as never)).toBe(true);
    });
});
