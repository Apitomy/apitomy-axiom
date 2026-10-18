import { render, screen, act } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../../config/api";
import { getWidget, type WidgetProps } from "../widget-registry";
import "./index";
import { processingIndicator, shortRef, sourceColor } from "./RecentEventsWidget";
import { toSubjectId } from "./SubjectSpotlightWidget";

vi.mock("../../../config/api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("../../../config/api")>()),
    fetchActivityLog: vi.fn(),
    fetchAiUsageSummary: vi.fn(),
    fetchUsage: vi.fn(),
    fetchSystemHealth: vi.fn(),
    fetchInboxItems: vi.fn(),
    fetchWaitingWorkflowRuns: vi.fn(),
    fetchSubject: vi.fn(),
    fetchSubjectMetrics: vi.fn(),
    fetchWorkflowRuns: vi.fn(),
    fetchActionTypes: vi.fn(),
    fetchStreamEvents: vi.fn(),
    fetchDiskUsage: vi.fn(),
}));

function renderWidget(type: string, config: Record<string, unknown> = {}, labels: string[] = []) {
    const entry = getWidget(type);
    if (!entry) throw new Error(`widget ${type} not registered`);
    const Component = entry.component;
    const props: WidgetProps = { config, labels, onConfigChange: () => {}, refreshToken: 0 };
    return render(<MemoryRouter><Component {...props} /></MemoryRouter>);
}

function page<T>(items: T[], totalCount = items.length) {
    return { items, totalCount, page: 1, limit: 20 } as unknown as api.SearchResults<T>;
}

afterEach(() => { vi.clearAllMocks(); vi.useRealTimers(); });

describe("registry", () => {
    it("keeps every existing widget type id", () => {
        for (const id of ["active-subjects", "subject-spotlight", "recent-activity", "inbox", "recent-events",
                          "ai-cost-summary", "ai-cost-by-subject", "system-status", "disk-usage", "action-types"]) {
            expect(getWidget(id)).toBeDefined();
        }
    });
});

describe("RecentActivityWidget", () => {
    it("links entries to subject, trace and event", async () => {
        vi.mocked(api.fetchActivityLog).mockResolvedValue(page([{
            id: 1, subjectId: 7, traceId: "t1", eventId: "e1", entryType: "workflow-failed",
            summary: "Workflow failed", createdOn: new Date().toISOString(),
        }]));
        renderWidget("recent-activity");
        expect(await screen.findByText("subject")).toHaveAttribute("href", "/subjects/7");
        expect(screen.getByText("trace")).toHaveAttribute("href", "/logs/traces/t1");
        expect(screen.getByText("event")).toHaveAttribute("href", "/events/stream/e1");
        expect(screen.getAllByText("Workflow failed").length).toBeGreaterThan(0);
    });
});

describe("AiCostBySubjectWidget", () => {
    it("uses the AI usage summary grouped by subject", async () => {
        vi.mocked(api.fetchAiUsageSummary).mockResolvedValue({
            groupBy: "subject", totalCostUsd: 3, excludedUnscopedCostUsd: 1.5,
            rows: [{ key: "1", label: "Alpha", costUsd: 3, inputTokens: 1, outputTokens: 1, invocations: 1 }],
        });
        renderWidget("ai-cost-by-subject", { timeWindow: "7d", maxSubjects: 5 }, ["team=a"]);
        expect(await screen.findByText(/Excludes \$1.50/)).toBeInTheDocument();
        expect(api.fetchAiUsageSummary).toHaveBeenCalledWith("subject",
            expect.objectContaining({ labels: ["team=a"], limit: 5 }));
    });
});

describe("AiCostSummaryWidget", () => {
    it("shows the excluded unscoped cost note when labels are set", async () => {
        vi.mocked(api.fetchUsage).mockResolvedValue({
            items: [], totalCount: 4, totalCostUsd: 2, totalInputTokens: 10, totalOutputTokens: 20,
        } as unknown as Awaited<ReturnType<typeof api.fetchUsage>>);
        vi.mocked(api.fetchAiUsageSummary).mockResolvedValue({
            groupBy: "subject", rows: [], totalCostUsd: 2, excludedUnscopedCostUsd: 0.25,
        });
        renderWidget("ai-cost-summary", {}, ["team=a"]);
        expect(await screen.findByText("$2.00")).toBeInTheDocument();
        expect(await screen.findByText(/Excludes \$0.25/)).toBeInTheDocument();
    });

    it("does not call the summary without labels", async () => {
        vi.mocked(api.fetchUsage).mockResolvedValue({
            items: [], totalCount: 0, totalCostUsd: 0, totalInputTokens: 0, totalOutputTokens: 0,
        } as unknown as Awaited<ReturnType<typeof api.fetchUsage>>);
        renderWidget("ai-cost-summary");
        expect(await screen.findByText("$0.00")).toBeInTheDocument();
        expect(api.fetchAiUsageSummary).not.toHaveBeenCalled();
    });
});

describe("SystemStatusWidget (Application Health)", () => {
    beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }); });

    it("is titled Application Health, renders checks and polls every 30s", async () => {
        expect(getWidget("system-status")?.name).toBe("Application Health");
        vi.mocked(api.fetchSystemHealth).mockResolvedValue({
            status: "DEGRADED", version: "3.0.0", timestamp: "",
            checks: [
                { name: "database", status: "UP" },
                { name: "eventSources", status: "DEGRADED", message: "1 failing" },
            ],
        });
        renderWidget("system-status");
        expect(await screen.findByText("DEGRADED", { selector: "span *, span" })).toBeInTheDocument();
        expect(screen.getByText("Event sources")).toHaveAttribute("href", "/events/sources");
        expect(screen.getByText(/1 failing/)).toBeInTheDocument();
        expect(api.fetchSystemHealth).toHaveBeenCalledTimes(1);
        await act(async () => { vi.advanceTimersByTime(30000); });
        expect(api.fetchSystemHealth).toHaveBeenCalledTimes(2);
    });

    it("shows DOWN when the endpoint fails", async () => {
        vi.mocked(api.fetchSystemHealth).mockRejectedValue(new Error("x"));
        renderWidget("system-status");
        expect(await screen.findByText("Health endpoint unreachable")).toBeInTheDocument();
    });
});

describe("InboxWidget", () => {
    it("shows task title, workflow run and deep links", async () => {
        vi.mocked(api.fetchInboxItems).mockResolvedValue(page([
            { id: 5, subjectId: 1, subjectName: "Sub", actionType: "review", status: "pending",
              humanContext: { title: "Approve release" }, createdOn: new Date().toISOString() },
            { id: 6, subjectId: 1, actionType: "triage", status: "pending", createdOn: new Date().toISOString() },
        ] as api.InboxItem[]));
        vi.mocked(api.fetchWaitingWorkflowRuns).mockResolvedValue([{
            runId: 9, workflowDefinitionId: 2, workflowName: "Release", nodeId: "n", waitKind: "human-task",
            waitingSince: new Date().toISOString(), taskId: 5,
        }]);
        renderWidget("inbox", {}, ["team=a"]);
        expect(await screen.findByText("Approve release")).toBeInTheDocument();
        expect(screen.getByText("triage")).toBeInTheDocument();
        expect(await screen.findByText("Release")).toHaveAttribute("href", "/runs/9");
        expect(getWidget("inbox")?.name).toBe("Tasks Awaiting Input");
        expect(getWidget("inbox")?.liveEvents).toContain("inbox-updated");
        expect(api.fetchWaitingWorkflowRuns).toHaveBeenCalledWith(["team=a"], 200);
    });

    it("shows the empty state", async () => {
        vi.mocked(api.fetchInboxItems).mockResolvedValue(page([]));
        vi.mocked(api.fetchWaitingWorkflowRuns).mockResolvedValue([]);
        renderWidget("inbox");
        expect(await screen.findByText("No tasks are waiting for input")).toBeInTheDocument();
    });
});

describe("SubjectSpotlightWidget", () => {
    it("treats legacy 0 and missing as no subject", () => {
        expect(toSubjectId(0)).toBeNull();
        expect(toSubjectId(undefined)).toBeNull();
        expect(toSubjectId(null)).toBeNull();
        expect(toSubjectId(4)).toBe(4);
        renderWidget("subject-spotlight", { subjectId: 0 });
        expect(screen.getByText(/No subject selected/)).toBeInTheDocument();
        expect(getWidget("subject-spotlight")?.configSchema?.[0].type).toBe("subject");
    });

    it("shows runs by status, waiting runs and AI cost", async () => {
        vi.mocked(api.fetchSubject).mockResolvedValue({ id: 3, name: "My subject", active: true } as api.Subject);
        vi.mocked(api.fetchSubjectMetrics).mockResolvedValue({
            subjectId: 3, diskUsageBytes: 0, totalCostUsd: 1.234, totalInputTokens: 0, totalOutputTokens: 0,
            invocationCount: 2,
        });
        vi.mocked(api.fetchWorkflowRuns).mockImplementation(async (_p, _l, f) => {
            if (f?.status === "waiting") {
                return page([{ runId: 11, subjectId: 3, definitionId: 1, definitionName: "Flow",
                               definitionVersion: 1, status: "waiting", startedOn: "" }], 1);
            }
            return page([], f?.status === "failed" ? 2 : 0);
        });
        renderWidget("subject-spotlight", { subjectId: 3 });
        expect(await screen.findByText("My subject")).toBeInTheDocument();
        expect(screen.getByText("failed: 2")).toBeInTheDocument();
        expect(screen.getByText("waiting: 1")).toBeInTheDocument();
        expect(screen.getByText("Flow")).toHaveAttribute("href", "/runs/11");
        expect(screen.getByText(/\$1.23/)).toBeInTheDocument();
    });

    it("explains a deleted subject", async () => {
        vi.mocked(api.fetchSubject).mockRejectedValue(new Error("Failed to fetch subject: 404"));
        renderWidget("subject-spotlight", { subjectId: 99 });
        expect(await screen.findByText(/Subject #99 no longer exists/)).toBeInTheDocument();
    });
});

describe("ActionTypesWidget", () => {
    it("lists server-side without dashboard labels and links rows", async () => {
        vi.mocked(api.fetchActionTypes).mockResolvedValue(page([
            { id: 1, name: "review", executionMode: "agent", labels: ["x=y"] },
        ] as api.ActionType[], 30));
        renderWidget("action-types", { maxRows: 10 }, ["team=a"]);
        expect(await screen.findByText("review")).toBeInTheDocument();
        expect(api.fetchActionTypes).toHaveBeenCalledWith(1, 10);
        expect(screen.getByText(/Showing 1 of 30/)).toHaveAttribute("href", "/action-types");
        expect(getWidget("action-types")?.scope).toBe("global");
        expect(getWidget("action-types")?.description).not.toMatch(/trigger/i);
    });
});

describe("RecentEventsWidget", () => {
    it("helpers", () => {
        expect(sourceColor("github")).toBe(sourceColor("github"));
        expect(processingIndicator(undefined).text).toBe("no match");
        expect(processingIndicator({ pending: 0, completed: 1, failed: 1, exhausted: 0, skipped: 0,
                                     filterError: 1 }).text).toBe("2 failed");
        expect(shortRef("a".repeat(40))).toHaveLength(24);
    });

    it("sorts by received time, filters by event source and shows processing", async () => {
        vi.mocked(api.fetchStreamEvents).mockResolvedValue(page([
            { id: "a", source: "github", eventSourceId: "s", type: "older", ref: "r1", timestamp: "2026-01-02T00:00:00Z",
              createdOn: "2026-01-01T00:00:00Z" },
            { id: "b", source: "jira", eventSourceId: "s", type: "newer", ref: "r2", timestamp: "2026-01-01T00:00:00Z",
              createdOn: "2026-01-03T00:00:00Z",
              processingStates: { pending: 1, completed: 0, failed: 0, exhausted: 0, skipped: 0, filterError: 0 } },
        ] as api.StreamEvent[]));
        renderWidget("recent-events", { maxRows: 10, eventSourceId: "s" });
        await screen.findByText("newer");
        const rows = screen.getAllByRole("row");
        expect(rows[1]).toHaveTextContent("newer");
        expect(rows[2]).toHaveTextContent("older");
        expect(screen.getByText("1 pending")).toBeInTheDocument();
        expect(api.fetchStreamEvents).toHaveBeenCalledWith(1, 10, undefined, "s");
    });
});

describe("DiskUsageWidget", () => {
    it("links to the disk usage page and is global", async () => {
        vi.mocked(api.fetchDiskUsage).mockResolvedValue({ items: [], totalCount: 0, totalDiskUsageBytes: 0 } as
            unknown as Awaited<ReturnType<typeof api.fetchDiskUsage>>);
        renderWidget("disk-usage");
        expect(await screen.findByText("Disk usage")).toHaveAttribute("href", "/metrics/disk-usage");
        expect(getWidget("disk-usage")?.scope).toBe("global");
    });
});

describe("refreshToken refetch", () => {
    async function expectRefetch(type: string, mock: ReturnType<typeof vi.fn>): Promise<void> {
        const entry = getWidget(type)!;
        const Component = entry.component;
        const props = (refreshToken: number): WidgetProps =>
            ({ config: {}, labels: [], onConfigChange: () => {}, refreshToken });
        const { rerender } = render(<MemoryRouter><Component {...props(0)} /></MemoryRouter>);
        await act(async () => {});
        expect(mock).toHaveBeenCalledTimes(1);
        rerender(<MemoryRouter><Component {...props(1)} /></MemoryRouter>);
        await act(async () => {});
        expect(mock).toHaveBeenCalledTimes(2);
    }

    it("ai-cost-by-subject refetches on refreshToken change", async () => {
        vi.mocked(api.fetchAiUsageSummary).mockResolvedValue({ rows: [] } as unknown as api.AiUsageSummary);
        await expectRefetch("ai-cost-by-subject", vi.mocked(api.fetchAiUsageSummary));
    });

    it("action-types refetches on refreshToken change", async () => {
        vi.mocked(api.fetchActionTypes).mockResolvedValue(page([]));
        await expectRefetch("action-types", vi.mocked(api.fetchActionTypes));
    });

    it("disk-usage refetches on refreshToken change", async () => {
        vi.mocked(api.fetchDiskUsage).mockResolvedValue(
            { items: [], totalCount: 0, totalDiskUsageBytes: 0 } as unknown as Awaited<ReturnType<typeof api.fetchDiskUsage>>);
        await expectRefetch("disk-usage", vi.mocked(api.fetchDiskUsage));
    });
});
