import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { SubscriptionItemEditor } from "./SubscriptionItemEditor";
import * as api from "../config/api";

vi.mock("../config/api", () => ({ previewSubscriptionFilter: vi.fn() }));

const base = { name: "on-pr", description: "", filterExpression: "event.type == 'pr.created'",
    routing: [{ type: "create-workflow", workflowDefinitionName: "wf" }] };

function renderEditor(over: Partial<Parameters<typeof SubscriptionItemEditor>[0]> = {}) {
    const props = { value: base, onChange: vi.fn(), workflowName: "wf", published: false, workflowPublished: false,
        enabled: false, onToggleEnabled: vi.fn(), ...over };
    render(<MemoryRouter><SubscriptionItemEditor {...props} /></MemoryRouter>);
    return props;
}

beforeEach(() => {
    vi.mocked(api.previewSubscriptionFilter).mockReset().mockResolvedValue({
        results: [
            { matched: true, event: { id: "e1", type: "pr.created", ref: "https://x/pull/1", actor: { login: "renovate[bot]" },
                timestamp: "2026-10-15T10:00:00Z" } },
            { matched: false, event: { id: "e2", type: "issue.created", ref: "https://x/issues/2", timestamp: "2026-10-15T09:00:00Z" } },
        ], totalMatched: 1, totalCount: 2, page: 1, limit: 200, errorCount: 0,
    } as never);
});

describe("SubscriptionItemEditor", () => {
    it("shows the fields and that start rules target the composition's workflow", () => {
        renderEditor();
        expect(screen.getByLabelText("Name")).toHaveValue("on-pr");
        expect(screen.getByLabelText("Filter expression")).toHaveValue("event.type == 'pr.created'");
        const table = screen.getByRole("grid", { name: "Routing rules" });
        expect(within(table).getAllByRole("columnheader").map((h) => h.textContent))
            .toEqual(["Rule", "Target", "Actions"]);
        const rows = within(table).getAllByRole("row").slice(1);
        expect(rows).toHaveLength(1);
        expect(rows[0]).toHaveTextContent("Start workflow");
        expect(rows[0]).toHaveTextContent("wf");
    });

    it("previews matching recent events", async () => {
        renderEditor();
        expect(await screen.findByText("1 of 2 recent events match")).toBeInTheDocument();
        const table = screen.getByRole("grid", { name: "Matching events" });
        expect(within(table).getAllByRole("columnheader").map((h) => h.textContent))
            .toEqual(["Type", "Ref", "Actor", "Time"]);
        const rows = within(table).getAllByRole("row").slice(1);
        expect(rows).toHaveLength(1);
        expect(within(rows[0]).getByRole("link", { name: "pr.created" })).toHaveAttribute("href", "/events/stream/e1");
        expect(rows[0]).toHaveTextContent("https://x/pull/1");
        expect(rows[0]).toHaveTextContent("renovate[bot]");
        expect(api.previewSubscriptionFilter).toHaveBeenCalledWith({ filterExpression: base.filterExpression, limit: 200 });
    });

    it("ignores a preview response for a filter that has since changed", async () => {
        const result = (type: string, matched: boolean) => ({
            results: [{ matched, event: { id: type, type, ref: "r", timestamp: "2026-10-15T10:00:00Z" } }],
            totalMatched: matched ? 1 : 0, totalCount: 1, page: 1, limit: 200, errorCount: 0,
        });
        let resolveOld: (v: unknown) => void = () => {};
        vi.mocked(api.previewSubscriptionFilter)
            .mockImplementationOnce(() => new Promise((r) => { resolveOld = r; }) as never)
            .mockResolvedValueOnce(result("new.type", false) as never);
        const props = { value: { ...base, filterExpression: "old" }, onChange: vi.fn(), workflowName: "wf",
            published: false, workflowPublished: false, enabled: false, onToggleEnabled: vi.fn() };
        const { rerender } = render(<MemoryRouter><SubscriptionItemEditor {...props} /></MemoryRouter>);
        await vi.waitFor(() => expect(api.previewSubscriptionFilter).toHaveBeenCalledTimes(1));
        rerender(<MemoryRouter>
            <SubscriptionItemEditor {...props} value={{ ...base, filterExpression: "new" }} />
        </MemoryRouter>);
        expect(await screen.findByText("0 of 1 recent events match")).toBeInTheDocument();
        resolveOld(result("old.type", true));
        await new Promise((r) => setTimeout(r, 0));
        expect(screen.getByText("0 of 1 recent events match")).toBeInTheDocument();
        expect(screen.queryByText("1 of 1 recent events match")).toBeNull();
    });

    it("adds a dispatch rule and a start rule bound to the workflow", async () => {
        const props = renderEditor({ value: { ...base, routing: [] } });
        await userEvent.click(screen.getByRole("button", { name: "Add start rule" }));
        expect(props.onChange).toHaveBeenLastCalledWith(expect.objectContaining({
            routing: [{ type: "create-workflow", workflowDefinitionName: "wf" }] }));
        await userEvent.click(screen.getByRole("button", { name: "Add dispatch rule" }));
        expect(props.onChange).toHaveBeenCalledWith(expect.objectContaining({
            routing: [{ type: "workflow-dispatch" }] }));
    });

    it("hides the Enabled switch until published and disables it while the workflow is unpublished", () => {
        renderEditor();
        expect(screen.queryByRole("switch", { name: "Enabled" })).toBeNull();
    });

    it("lists dispatch rules and flags a start rule that targets another workflow", async () => {
        const props = renderEditor({ value: { ...base, routing: [
            { type: "create-workflow", workflowDefinitionName: "other-wf" }, { type: "workflow-dispatch" }] } });
        const rows = within(screen.getByRole("grid", { name: "Routing rules" })).getAllByRole("row").slice(1);
        expect(rows[0]).toHaveTextContent("other-wf");
        expect(rows[0]).toHaveTextContent("Not this composition's workflow");
        expect(rows[1]).toHaveTextContent("Dispatch to waiting runs");
        expect(rows[1]).toHaveTextContent("receive-event nodes");
        await userEvent.click(within(rows[1]).getByRole("button", { name: "Remove rule 2" }));
        expect(props.onChange).toHaveBeenLastCalledWith(expect.objectContaining({
            routing: [{ type: "create-workflow", workflowDefinitionName: "other-wf" }] }));
    });

    it("shows an empty state when there are no rules", () => {
        renderEditor({ value: { ...base, routing: [] } });
        expect(screen.queryByRole("grid", { name: "Routing rules" })).toBeNull();
        expect(screen.getByText("No routing rules: matching events do nothing.")).toBeInTheDocument();
    });

    it("does not crash when routing is not an array", () => {
        renderEditor({ value: { ...base, routing: {} } });
        expect(screen.getByLabelText("Name")).toHaveValue("on-pr");
        expect(screen.queryByRole("button", { name: /Remove rule/ })).toBeNull();
    });

    it("disables the Enabled switch while a toggle is saving", () => {
        renderEditor({ published: true, workflowPublished: true, enabledBusy: true });
        expect(screen.getByRole("switch", { name: "Enabled" })).toBeDisabled();
    });

    it("toggles enabled when published", async () => {
        const props = renderEditor({ published: true, workflowPublished: true });
        await userEvent.click(screen.getByRole("switch", { name: "Enabled" }));
        expect(props.onToggleEnabled).toHaveBeenCalledWith(true);
    });

    it("keeps the switch disabled while the workflow is unpublished", () => {
        renderEditor({ published: true, workflowPublished: false });
        expect(screen.getByRole("switch", { name: "Enabled" })).toBeDisabled();
    });
});
