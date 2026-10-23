import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../config/api";
import type { JsonSchema } from "../config/eventSchemaPaths";
import { clearEventSchemaCache } from "../hooks/useEventSchemas";
import { EventFieldsPanel } from "./EventFieldsPanel";

vi.mock("../config/api", () => ({
    fetchEventSchemas: vi.fn(),
    fetchEventSchema: vi.fn(),
}));

function schemaFor(type: string): JsonSchema {
    return {
        title: type,
        description: `Schema for ${type}`,
        type: "object",
        properties: {
            type: { type: "string", const: type, description: "Normalized event type" },
            payload: {
                type: "object",
                description: "Type-specific payload",
                properties: {
                    issue: {
                        type: "object",
                        description: "The issue",
                        properties: {
                            state: { type: "string", enum: ["open", "closed"], description: "Normalized state" },
                        },
                    },
                },
            },
            sourceData: { description: "Raw source data" },
        },
    };
}

beforeEach(() => {
    clearEventSchemaCache();
    vi.mocked(api.fetchEventSchemas).mockReset().mockResolvedValue([
        { type: "issue.closed" }, { type: "issue.created" }, { type: "pr.merged" },
    ]);
    vi.mocked(api.fetchEventSchema).mockReset().mockImplementation(async (t: string) => schemaFor(t));
});

describe("EventFieldsPanel", () => {
    it("offers all types and defaults to issue.created when the filter does not name a type", async () => {
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} />);
        const select = await screen.findByRole("combobox", { name: "Event type" });
        await waitFor(() => expect(select).toHaveValue("issue.created"));
        expect(screen.getAllByRole("option")).toHaveLength(3);
        expect(api.fetchEventSchema).toHaveBeenCalledWith("issue.created");
    });

    it("offers only the types named in the filter expression", async () => {
        render(<EventFieldsPanel filterExpression="event.type == 'pr.merged'" onSelectPath={vi.fn()} />);
        const select = await screen.findByRole("combobox", { name: "Event type" });
        await waitFor(() => expect(select).toHaveValue("pr.merged"));
        expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["pr.merged"]);
        expect(screen.getByText(/event types used in your filter expression/)).toBeInTheDocument();
    });

    it("lists path, type, description and example", async () => {
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} />);
        expect(await screen.findByText("event.payload.issue.state")).toBeInTheDocument();
        expect(screen.getByText("Normalized state")).toBeInTheDocument();
        expect(screen.getByText("\"open\"")).toBeInTheDocument();
        expect(screen.getByText("any (source-specific)")).toBeInTheDocument();
    });

    it("calls onSelectPath when a path is clicked", async () => {
        const onSelectPath = vi.fn();
        render(<EventFieldsPanel filterExpression="" onSelectPath={onSelectPath} />);
        await userEvent.click(await screen.findByRole("button", { name: "Insert event.payload.issue.state" }));
        expect(onSelectPath).toHaveBeenCalledWith("event.payload.issue.state");
    });

    it("loads the selected type's schema and reports its rows", async () => {
        const onRowsChange = vi.fn();
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} onRowsChange={onRowsChange} />);
        const select = await screen.findByRole("combobox", { name: "Event type" });
        await screen.findByText("event.payload.issue.state");
        await userEvent.selectOptions(select, "issue.closed");
        await waitFor(() => expect(api.fetchEventSchema).toHaveBeenCalledWith("issue.closed"));
        expect(await screen.findByText("Schema for issue.closed")).toBeInTheDocument();
        await waitFor(() => expect(onRowsChange).toHaveBeenLastCalledWith(
            expect.arrayContaining([expect.objectContaining({ path: "event.type", examples: ["issue.closed"] })])));
    });

    it("shows a warning when the type list cannot be loaded", async () => {
        vi.mocked(api.fetchEventSchemas).mockRejectedValue(new Error("offline"));
        render(<EventFieldsPanel filterExpression="" onSelectPath={vi.fn()} />);
        expect(await screen.findByText("Event field reference is unavailable")).toBeInTheDocument();
        expect(screen.getByText("offline")).toBeInTheDocument();
    });
});
