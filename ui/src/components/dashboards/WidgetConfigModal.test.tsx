import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WidgetConfigModal } from "./WidgetConfigModal";
import type { ConfigField } from "./widget-registry";

const page = <T,>(items: T[]) => ({ items, totalCount: items.length, page: 1, limit: 50 });

vi.mock("../../config/api", () => ({
    fetchSubjects: vi.fn((_p: number, _l: number, name?: string) => Promise.resolve(page(
        [{ id: 1, name: "Alpha" }, { id: 2, name: "Beta" }].filter((s) => !name || s.name.includes(name))))),
    fetchSubject: vi.fn((id: number) => id === 9
        ? Promise.reject(new Error("404")) : Promise.resolve({ id, name: id === 2 ? "Beta" : "Alpha" })),
    fetchWorkflowDefinitions: vi.fn(() => Promise.resolve(page([{ id: 5, name: "Triage" }]))),
    getWorkflowDefinition: vi.fn((id: number) => Promise.resolve({ id, name: "Triage" })),
    fetchEventSources: vi.fn(() => Promise.resolve(page([{ id: "gh-main", name: "GitHub main" }]))),
    fetchEventSource: vi.fn((id: string) => Promise.resolve({ id, name: "GitHub main" })),
    fetchSubscriptions: vi.fn(() => Promise.resolve(page([{ id: 7, name: "All issues" }]))),
    fetchSubscription: vi.fn((id: number) => Promise.resolve({ id, name: "All issues" })),
}));

function renderModal(configSchema: ConfigField[], config: Record<string, unknown>, onSave = vi.fn()) {
    render(<WidgetConfigModal isOpen widgetName="Test" configSchema={configSchema} config={config}
                              onSave={onSave} onCancel={vi.fn()} />);
    return onSave;
}

describe("WidgetConfigModal", () => {
    it("saves a toggle value", async () => {
        const onSave = renderModal([{ key: "compact", label: "Compact", type: "toggle", default: false }], {});
        await userEvent.click(screen.getByRole("switch", { name: "Compact" }));
        await userEvent.click(screen.getByRole("button", { name: "Save" }));
        expect(onSave).toHaveBeenCalledWith({ compact: true });
    });

    it("saves multiselect values as an array and accepts legacy string values", async () => {
        const onSave = renderModal([{
            key: "statuses", label: "Statuses", type: "multiselect", default: [],
            options: [{ label: "Failed", value: "failed" }, { label: "Running", value: "running" }],
        }], { statuses: "failed" });
        const toggle = screen.getByRole("button", { name: "Statuses" });
        expect(toggle).toHaveTextContent("Failed");
        await userEvent.click(toggle);
        await userEvent.click(await screen.findByRole("checkbox", { name: /Running/ }));
        await userEvent.click(screen.getByRole("button", { name: "Save" }));
        expect(onSave).toHaveBeenCalledWith({ statuses: ["failed", "running"] });
    });

    it("selects a subject by name and stores its numeric id", async () => {
        const onSave = renderModal([{ key: "subjectId", label: "Subject", type: "subject", default: null }], {});
        const select = screen.getByRole("combobox", { name: "Subject" });
        await screen.findByRole("option", { name: "Beta" });
        await userEvent.selectOptions(select, "2");
        await userEvent.click(screen.getByRole("button", { name: "Save" }));
        expect(onSave).toHaveBeenCalledWith({ subjectId: 2 });
    });

    it("filters subjects by the search text", async () => {
        renderModal([{ key: "subjectId", label: "Subject", type: "subject", default: null }], {});
        await screen.findByRole("option", { name: "Beta" });
        await userEvent.type(screen.getByRole("textbox", { name: "Search Subject" }), "Alp");
        await waitFor(() => expect(screen.queryByRole("option", { name: "Beta" })).not.toBeInTheDocument());
        expect(screen.getByRole("option", { name: "Alpha" })).toBeInTheDocument();
    });

    it("shows a missing current subject as not found", async () => {
        renderModal([{ key: "subjectId", label: "Subject", type: "subject", default: null }], { subjectId: 9 });
        expect(await screen.findByRole("option", { name: "#9 (not found)" })).toBeInTheDocument();
    });

    it("supports workflow, event source and subscription selectors", async () => {
        const onSave = renderModal([
            { key: "workflowId", label: "Workflow", type: "workflow", default: null },
            { key: "eventSourceId", label: "Event source", type: "eventSource", default: null },
            { key: "subscriptionId", label: "Subscription", type: "subscription", default: null },
        ], {});
        await screen.findByRole("option", { name: "Triage" });
        await screen.findByRole("option", { name: "GitHub main" });
        await screen.findByRole("option", { name: "All issues" });
        await userEvent.selectOptions(screen.getByRole("combobox", { name: "Workflow" }), "5");
        await userEvent.selectOptions(screen.getByRole("combobox", { name: "Event source" }), "gh-main");
        await userEvent.selectOptions(screen.getByRole("combobox", { name: "Subscription" }), "7");
        await userEvent.click(screen.getByRole("button", { name: "Save" }));
        expect(onSave).toHaveBeenCalledWith({ workflowId: 5, eventSourceId: "gh-main", subscriptionId: 7 });
    });
});
