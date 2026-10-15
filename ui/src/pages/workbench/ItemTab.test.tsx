import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as c from "../../config/compositions";
import { ItemTab, type ItemTabHandle, problemPrefill } from "./ItemTab";

vi.mock("../../components/ToolEditor", () => ({
    ToolEditor: ({ value, onChange, readOnly }: { value: { description?: string }; onChange: (v: unknown) => void; readOnly?: boolean }) => (
        <input aria-label="description" value={value.description ?? ""} disabled={readOnly}
            onChange={(e) => onChange({ ...value, description: e.target.value })} />),
}));
vi.mock("../../components/ActionTypeEditor", () => ({ ActionTypeEditor: () => <div /> }));

const item = { id: 5, type: "tool", name: "t", contentRevision: "r0", lastAuthor: "user", changed: true, lastTurnId: "t9" } as c.CompositionItem;
const composition = { id: 1, name: "C", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" } as c.Composition;

vi.mock("../../components/SubscriptionItemEditor", () => ({
    SubscriptionItemEditor: ({ value, workflowName }: { value: { filterExpression?: string }; workflowName: string }) => (
        <input aria-label="Filter expression" data-workflow={workflowName} value={value.filterExpression ?? ""} readOnly />),
}));

describe("ItemTab", () => {
    afterEach(() => vi.restoreAllMocks());

    it("renders the subscription editor for a subscription item", () => {
        render(<ItemTab compositionId={1} item={{ ...item, type: "subscription" }} content={{ name: "s", filterExpression: "true" }}
            composition={composition} problems={[]} onSaved={() => {}} workflowName="wf" />);
        expect(screen.getByLabelText("Filter expression")).toHaveAttribute("data-workflow", "wf");
        expect(screen.queryByLabelText("description")).toBeNull();
    });
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
        expect(screen.getByText(/the ai is editing this item/i).closest(".pf-v6-c-banner.pf-m-info")).toBeTruthy();
    });

    it("adopts the revision with external content", async () => {
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...item, contentRevision: "r3" });
        const props = { compositionId: 1, item, composition, problems: [], onSaved: () => {} };
        const { rerender } = render(<ItemTab {...props} content={{ name: "t", description: "" }} contentRevision="r0" />);
        rerender(<ItemTab {...props} content={{ name: "t", description: "ai" }} contentRevision="r2" />);
        expect((screen.getByLabelText("description") as HTMLInputElement).value).toBe("ai");
        fireEvent.change(screen.getByLabelText("description"), { target: { value: "mine" } });
        await waitFor(() => expect(save).toHaveBeenCalledWith(1, 5,
            { content: { name: "t", description: "mine" }, baseRevision: "r2" }), { timeout: 2000 });
    });

    it("keeps the local value while a save is pending", () => {
        vi.spyOn(c, "saveItem").mockResolvedValue({ ...item, contentRevision: "r1" });
        const props = { compositionId: 1, item, composition, problems: [], onSaved: () => {} };
        const { rerender } = render(<ItemTab {...props} content={{ name: "t", description: "" }} contentRevision="r0" />);
        fireEvent.change(screen.getByLabelText("description"), { target: { value: "mine" } });
        rerender(<ItemTab {...props} content={{ name: "t", description: "ai" }} contentRevision="r2" />);
        expect((screen.getByLabelText("description") as HTMLInputElement).value).toBe("mine");
    });

    it("offers Reload on 409, resetting the value and base", async () => {
        const save = vi.spyOn(c, "saveItem").mockRejectedValueOnce(new c.ApiError("conflict", 409, null))
            .mockResolvedValue({ ...item, contentRevision: "r6" });
        vi.spyOn(c, "fetchItem").mockResolvedValue({ ...item, contentRevision: "r5",
            content: { name: "t", description: "server" } });
        render(<ItemTab compositionId={1} item={item} content={{ name: "t", description: "" }} composition={composition}
            problems={[]} onSaved={() => {}} />);
        fireEvent.change(screen.getByLabelText("description"), { target: { value: "x" } });
        fireEvent.click(await screen.findByText("Reload", {}, { timeout: 2000 }));
        await waitFor(() => expect((screen.getByLabelText("description") as HTMLInputElement).value).toBe("server"));
        expect(screen.queryByText("Changed elsewhere")).toBeNull();
        fireEvent.change(screen.getByLabelText("description"), { target: { value: "y" } });
        await waitFor(() => expect(save).toHaveBeenLastCalledWith(1, 5,
            { content: { name: "t", description: "y" }, baseRevision: "r5" }), { timeout: 2000 });
    });

    it("flush() saves a scheduled edit immediately", async () => {
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...item, contentRevision: "r1" });
        const ref: { current: ItemTabHandle | null } = { current: null };
        render(<ItemTab ref={ref} compositionId={1} item={item} content={{ name: "t", description: "" }}
            composition={composition} problems={[]} onSaved={() => {}} />);
        fireEvent.change(screen.getByLabelText("description"), { target: { value: "now" } });
        expect(save).not.toHaveBeenCalled();
        await ref.current!.flush();
        expect(save).toHaveBeenCalledWith(1, 5, { content: { name: "t", description: "now" }, baseRevision: "r0" });
    });

    it("builds the Ask AI prefill for a problem", () => {
        expect(problemPrefill({ ...item, name: "gh" } as never,
            { severity: "error", field: "scriptTemplate", message: "is required" } as never))
            .toBe("Please fix this problem in tool `gh` (field `scriptTemplate`): is required");
    });
});
