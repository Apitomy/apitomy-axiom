import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PublishDialog } from "./PublishDialog";
import * as c from "../../config/compositions";

const items = [
    { id: 1, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: true },
    { id: 2, type: "action-type", name: "at", contentRevision: "r", lastAuthor: "ai", changed: true },
] as c.CompositionItem[];
const contents = { 1: { nodes: [{ id: "a", type: "action", config: { actionType: "at" } }], edges: [] }, 2: { name: "at" } };

describe("PublishDialog", () => {
    it("auto-selects dependencies and forces after a conflict", async () => {
        const publish = vi.spyOn(c, "publishComposition")
            .mockRejectedValueOnce(new c.ApiError("Changed outside the workbench since loaded: action-type/at", 409, {}))
            .mockResolvedValueOnce({ created: 1, updated: 1, items: [] });
        const onPublished = vi.fn();
        render(<PublishDialog compositionId={9} items={items} contents={contents} problems={{ 1: [], 2: [] }}
            onPublished={onPublished} onClose={() => {}} />);
        fireEvent.click(screen.getByLabelText("at"));
        fireEvent.click(screen.getByLabelText("at"));
        expect((screen.getByLabelText("at") as HTMLInputElement).checked).toBe(true);
        fireEvent.click(screen.getByRole("button", { name: /^publish$/i }));
        await screen.findByText(/changed outside the workbench/i);
        fireEvent.click(screen.getByRole("button", { name: /publish anyway/i }));
        await waitFor(() => expect(onPublished).toHaveBeenCalled());
        expect(publish).toHaveBeenLastCalledWith(9, [1, 2], true);
    });

    it("lists blocking problems on 422", async () => {
        vi.spyOn(c, "publishComposition").mockRejectedValue(new c.ApiError("Cannot publish", 422,
            { message: "Cannot publish", problems: { "workflow/wf": [{ severity: "error", code: "x", message: "broken node" }] } }));
        render(<PublishDialog compositionId={9} items={items} contents={contents} problems={{}} onPublished={() => {}} onClose={() => {}} />);
        fireEvent.click(screen.getByRole("button", { name: /^publish$/i }));
        expect(await screen.findByText(/broken node/)).toBeTruthy();
    });
});
