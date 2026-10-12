import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as c from "../../config/compositions";
import { ItemTab } from "./ItemTab";

vi.mock("../../components/ToolEditor", () => ({
    ToolEditor: ({ value, onChange, readOnly }: { value: { description?: string }; onChange: (v: unknown) => void; readOnly?: boolean }) => (
        <input aria-label="description" value={value.description ?? ""} disabled={readOnly}
            onChange={(e) => onChange({ ...value, description: e.target.value })} />),
}));
vi.mock("../../components/ActionTypeEditor", () => ({ ActionTypeEditor: () => <div /> }));

const item = { id: 5, type: "tool", name: "t", contentRevision: "r0", lastAuthor: "user", changed: true, lastTurnId: "t9" } as c.CompositionItem;
const composition = { id: 1, name: "C", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" } as c.Composition;

describe("ItemTab", () => {
    it("saves edits with the base revision", async () => {
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...item, contentRevision: "r1" });
        render(<ItemTab compositionId={1} item={item} content={{ name: "t", description: "" }} composition={composition}
            problems={[]} onSaved={() => {}} />);
        fireEvent.change(screen.getByLabelText("description"), { target: { value: "new" } });
        await waitFor(() => expect(save).toHaveBeenCalledWith(1, 5, { content: { name: "t", description: "new" }, baseRevision: "r0" }), { timeout: 2000 });
    });

    it("is read-only while the AI edits it in the running turn", () => {
        render(<ItemTab compositionId={1} item={item} content={{ name: "t" }}
            composition={{ ...composition, turnState: "ai-working", currentTurnId: "t9" }} problems={[]} onSaved={() => {}} />);
        expect((screen.getByLabelText("description") as HTMLInputElement).disabled).toBe(true);
        expect(screen.getByText(/the ai is editing this item/i)).toBeTruthy();
    });
});
