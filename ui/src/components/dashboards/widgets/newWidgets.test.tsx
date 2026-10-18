import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../../../config/api";
import { getWidget, type WidgetProps } from "../widget-registry";
import "./index";
import { bucketFor } from "./EventVolumeWidget";
import { EVENT_SOURCE_HEALTH_POLL_MS } from "./EventSourceHealthWidget";
import { rowLink } from "./AiUsageBreakdownWidget";
import { buildWorkflowRows } from "./WorkflowsWidget";
import { formatAge } from "./widgetUtils";

vi.mock("../../../config/api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("../../../config/api")>()),
    fetchWorkflowRunStatusSummary: vi.fn(),
    rerunWorkflowRun: vi.fn(),
    fetchWaitingWorkflowRuns: vi.fn(),
    fetchWorkflowSchedules: vi.fn(),
    fetchEventSourceHealth: vi.fn(),
    fetchSubscriptionProcessingSummary: vi.fn(),
    fetchProcessingFailures: vi.fn(),
    retryEventProcessing: vi.fn(),
    fetchEventVolumeHistogram: vi.fn(),
    fetchAiUsageSummary: vi.fn(),
    fetchWorkflowDefinitions: vi.fn(),
    fetchWorkflowRuns: vi.fn(),
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

const counts = { running: 2, waiting: 3, completed: 40, failed: 5, cancelled: 1 };
const failedRun = {
    runId: 11, subjectId: 7, subjectName: "Repo A", definitionId: 3, definitionName: "Triage",
    definitionVersion: 1, status: "failed", startedOn: new Date().toISOString(),
    completedOn: new Date().toISOString(),
} as api.WorkflowRunSummary;

afterEach(() => { vi.clearAllMocks(); vi.useRealTimers(); });

describe("new widget registry entries", () => {
    const expected: Record<string, [string, string]> = {
        "workflow-runs-summary": ["Runs", "subject-labels"],
        "failed-runs": ["Runs", "subject-labels"],
        "waiting-runs": ["Runs", "subject-labels"],
        "upcoming-schedules": ["Runs", "global"],
        "workflows": ["Runs", "global"],
        "event-source-health": ["Events", "global"],
        "subscription-processing": ["Events", "global"],
        "routing-failures": ["Events", "global"],
        "event-volume": ["Events", "global"],
        "ai-usage-breakdown": ["AI & Cost", "subject-labels"],
    };
    it.each(Object.entries(expected))("%s is registered with category and scope", (id, [category, scope]) => {
        const entry = getWidget(id);
        expect(entry).toBeDefined();
        expect(entry?.category).toBe(category);
        expect(entry?.scope).toBe(scope);
    });
    it("declares live events", () => {
        expect(getWidget("waiting-runs")?.liveEvents).toEqual(["workflow-updated", "inbox-updated"]);
        expect(getWidget("routing-failures")?.liveEvents).toEqual(["stream-processing"]);
        expect(getWidget("subscription-processing")?.liveEvents).toEqual(["stream-processing"]);
        expect(getWidget("event-volume")?.liveEvents).toEqual(["stream-event"]);
        expect(getWidget("event-source-health")?.liveEvents).toBeUndefined();
    });
});

describe("helpers", () => {
    it("formats ages", () => {
        const now = Date.parse("2026-01-03T00:00:00Z");
        expect(formatAge("2026-01-02T23:55:00Z", now)).toBe("5m");
        expect(formatAge("2026-01-02T21:00:00Z", now)).toBe("3h");
        expect(formatAge("2025-12-30T00:00:00Z", now)).toBe("4d");
        expect(formatAge(undefined, now)).toBe("-");
    });
    it("keeps histogram buckets under the cap", () => {
        expect(bucketFor("24h")).toBe("hour");
        expect(bucketFor("7d")).toBe("hour");
        expect(bucketFor("30d")).toBe("day");
        expect(bucketFor("90d")).toBe("day");
    });
    it("links AI usage rows", () => {
        expect(rowLink("subject", "5")).toBe("/subjects/5");
        expect(rowLink("workflow", "3")).toBe("/components/workflows/3");
        expect(rowLink("model", "gpt")).toBeUndefined();
        expect(rowLink("workflow", "")).toBeUndefined();
    });
    it("maps last run per workflow from one runs page", () => {
        const defs = [{ id: 3, name: "A" }, { id: 4, name: "B" }] as api.WorkflowDefinition[];
        const older = { ...failedRun, runId: 9, status: "completed" };
        const rows = buildWorkflowRows(defs, [failedRun, older], [
            { triggerId: 1, workflowDefinitionId: 3, workflowName: "A", cron: "* * * * *", enabled: true },
            { triggerId: 2, workflowDefinitionId: 3, workflowName: "A", cron: "* * * * *", enabled: false },
        ]);
        expect(rows[0].lastRun?.runId).toBe(11);
        expect(rows[0].enabledSchedules).toBe(1);
        expect(rows[0].totalSchedules).toBe(2);
        expect(rows[1].lastRun).toBeUndefined();
    });
});

describe("WorkflowRunsSummaryWidget", () => {
    it("shows counts and passes labels and since", async () => {
        vi.mocked(api.fetchWorkflowRunStatusSummary).mockResolvedValue({ counts, recentFailed: [] });
        renderWidget("workflow-runs-summary", { since: "7d" }, ["team=a"]);
        expect(await screen.findByTestId("runs-completed")).toHaveTextContent("40");
        expect(screen.getByTestId("runs-failed")).toHaveTextContent("5");
        expect(api.fetchWorkflowRunStatusSummary).toHaveBeenCalledWith(
            expect.objectContaining({ labels: ["team=a"], since: expect.any(String) }));
    });
    it("shows an error", async () => {
        vi.mocked(api.fetchWorkflowRunStatusSummary).mockRejectedValue(new Error("x"));
        renderWidget("workflow-runs-summary");
        expect(await screen.findByText("Error loading data.")).toBeInTheDocument();
    });
});

describe("FailedRunsWidget", () => {
    it("shows the empty state", async () => {
        vi.mocked(api.fetchWorkflowRunStatusSummary).mockResolvedValue({ counts, recentFailed: [] });
        renderWidget("failed-runs");
        expect(await screen.findByText("No failed runs in this window.")).toBeInTheDocument();
    });
    it("lists failed runs and re-runs only on click", async () => {
        vi.mocked(api.fetchWorkflowRunStatusSummary).mockResolvedValue({ counts, recentFailed: [failedRun] });
        vi.mocked(api.rerunWorkflowRun).mockResolvedValue({ id: 99 } as api.WorkflowInstanceInfo);
        renderWidget("failed-runs", { maxRows: 5 });
        expect(await screen.findByText("Triage")).toHaveAttribute("href", "/runs/11");
        expect(screen.getByText("Repo A")).toHaveAttribute("href", "/subjects/7");
        expect(api.rerunWorkflowRun).not.toHaveBeenCalled();
        expect(api.fetchWorkflowRunStatusSummary).toHaveBeenCalledWith(expect.objectContaining({ failedLimit: 5 }));
        fireEvent.click(screen.getByRole("button", { name: "Re-run run 11" }));
        expect(await screen.findByText("Run #11 re-run started.")).toBeInTheDocument();
        expect(screen.getByText("View new run")).toHaveAttribute("href", "/runs/99");
        expect(api.rerunWorkflowRun).toHaveBeenCalledWith(11);
        await waitFor(() => expect(api.fetchWorkflowRunStatusSummary).toHaveBeenCalledTimes(2));
    });
    it("shows a re-run error inline", async () => {
        vi.mocked(api.fetchWorkflowRunStatusSummary).mockResolvedValue({ counts, recentFailed: [failedRun] });
        vi.mocked(api.rerunWorkflowRun).mockRejectedValue(new Error("Workflow is not published"));
        renderWidget("failed-runs");
        fireEvent.click(await screen.findByRole("button", { name: "Re-run run 11" }));
        expect(await screen.findByText("Workflow is not published")).toBeInTheDocument();
    });
});

describe("WaitingRunsWidget", () => {
    it("lists waiting runs with kind-specific details", async () => {
        vi.mocked(api.fetchWaitingWorkflowRuns).mockResolvedValue([
            { runId: 1, workflowDefinitionId: 3, workflowName: "Review", nodeId: "n1", waitKind: "human-task",
              waitingSince: new Date().toISOString(), taskId: 42, subjectId: 7, subjectName: "Repo A" },
            { runId: 2, workflowDefinitionId: 3, workflowName: "Sync", nodeId: "n2", waitKind: "receive-event",
              waitingSince: new Date().toISOString(), eventType: "pr.merged", correlationKey: "pr-5" },
        ]);
        renderWidget("waiting-runs", { maxRows: 20 }, ["team=a"]);
        expect(await screen.findByText("open task")).toHaveAttribute("href", "/inbox?task=42");
        expect(screen.getByText("Review")).toHaveAttribute("href", "/runs/1");
        expect(screen.getByText("pr.merged (pr-5)")).toBeInTheDocument();
        expect(api.fetchWaitingWorkflowRuns).toHaveBeenCalledWith(["team=a"], 20);
    });
    it("shows empty and error states", async () => {
        vi.mocked(api.fetchWaitingWorkflowRuns).mockResolvedValueOnce([]);
        const { unmount } = renderWidget("waiting-runs");
        expect(await screen.findByText("No runs are waiting.")).toBeInTheDocument();
        unmount();
        vi.mocked(api.fetchWaitingWorkflowRuns).mockRejectedValueOnce(new Error("x"));
        renderWidget("waiting-runs");
        expect(await screen.findByText("Error loading data.")).toBeInTheDocument();
    });
});

describe("UpcomingSchedulesWidget", () => {
    const schedules: api.WorkflowScheduleSummary[] = [
        { triggerId: 1, workflowDefinitionId: 3, workflowName: "Nightly", cron: "0 0 * * *", enabled: true,
          nextFireAt: new Date().toISOString(), lastOutcome: "started", lastRunId: 50 },
        { triggerId: 2, workflowDefinitionId: 4, workflowName: "Off", cron: "0 1 * * *", enabled: false },
    ];
    it("hides disabled schedules by default", async () => {
        vi.mocked(api.fetchWorkflowSchedules).mockResolvedValue(schedules);
        renderWidget("upcoming-schedules");
        expect(await screen.findByText("Nightly")).toHaveAttribute("href", "/components/workflows/3");
        expect(screen.getByText("started").closest("a")).toHaveAttribute("href", "/runs/50");
        expect(screen.queryByText("Off")).not.toBeInTheDocument();
    });
    it("shows disabled schedules when configured", async () => {
        vi.mocked(api.fetchWorkflowSchedules).mockResolvedValue(schedules);
        renderWidget("upcoming-schedules", { showDisabled: true });
        expect(await screen.findByText("Off")).toBeInTheDocument();
    });
    it("shows the empty state", async () => {
        vi.mocked(api.fetchWorkflowSchedules).mockResolvedValue([]);
        renderWidget("upcoming-schedules");
        expect(await screen.findByText("No scheduled workflows.")).toBeInTheDocument();
    });
});

describe("EventSourceHealthWidget", () => {
    it("shows failing sources first with the last error", async () => {
        vi.mocked(api.fetchEventSourceHealth).mockResolvedValue([
            { id: "a", name: "Alpha", type: "github", enabled: true, state: "healthy", consecutiveFailures: 0,
              eventsLast24h: 12, lastSuccessAt: new Date().toISOString() },
            { id: "b", name: "Beta", type: "jira", enabled: true, state: "failing", consecutiveFailures: 3,
              eventsLast24h: 0, lastError: "401 Unauthorized" },
        ]);
        renderWidget("event-source-health");
        const links = await screen.findAllByRole("link");
        expect(links[0]).toHaveTextContent("Beta");
        expect(links[0]).toHaveAttribute("href", "/events/sources/b");
        expect(screen.getByText("401 Unauthorized")).toBeInTheDocument();
        expect(screen.getByText("3 consecutive failures")).toBeInTheDocument();
    });
    it("polls every minute and stops on unmount", async () => {
        vi.useFakeTimers();
        vi.mocked(api.fetchEventSourceHealth).mockResolvedValue([]);
        const { unmount } = renderWidget("event-source-health");
        await act(async () => { await Promise.resolve(); });
        expect(api.fetchEventSourceHealth).toHaveBeenCalledTimes(1);
        await act(async () => { vi.advanceTimersByTime(EVENT_SOURCE_HEALTH_POLL_MS); });
        expect(api.fetchEventSourceHealth).toHaveBeenCalledTimes(2);
        unmount();
        await act(async () => { vi.advanceTimersByTime(EVENT_SOURCE_HEALTH_POLL_MS * 3); });
        expect(api.fetchEventSourceHealth).toHaveBeenCalledTimes(2);
    });
    it("shows an error", async () => {
        vi.mocked(api.fetchEventSourceHealth).mockRejectedValue(new Error("x"));
        renderWidget("event-source-health");
        expect(await screen.findByText("Error loading data.")).toBeInTheDocument();
    });
});

describe("SubscriptionProcessingWidget", () => {
    const zero = { pending: 0, completed: 0, failed: 0, exhausted: 0, skipped: 0, filterError: 0 };
    it("lists active subscriptions with failure totals", async () => {
        vi.mocked(api.fetchSubscriptionProcessingSummary).mockResolvedValue([
            { subscriptionId: 1, name: "Idle", enabled: true, counts: zero },
            { subscriptionId: 2, name: "PRs", enabled: true,
              counts: { ...zero, completed: 10, failed: 1, exhausted: 2, filterError: 1 } },
        ]);
        renderWidget("subscription-processing", { since: "7d" });
        expect(await screen.findByText("PRs")).toHaveAttribute("href", "/events/subscriptions/2");
        expect(screen.getByText("4")).toBeInTheDocument();
        expect(screen.queryByText("Idle")).not.toBeInTheDocument();
        expect(api.fetchSubscriptionProcessingSummary).toHaveBeenCalledWith(expect.any(String));
    });
    it("shows the empty state", async () => {
        vi.mocked(api.fetchSubscriptionProcessingSummary).mockResolvedValue([]);
        renderWidget("subscription-processing");
        expect(await screen.findByText("No events processed in this window.")).toBeInTheDocument();
    });
});

describe("RoutingFailuresWidget", () => {
    const failure: api.ProcessingFailure = {
        ledgerId: 77, eventId: "e-1", eventType: "pr.opened", subscriptionId: 2, subscriptionName: "PRs",
        status: "exhausted", errorMessage: "boom", attemptCount: 5, updatedAt: new Date().toISOString(),
    };
    it("retries only on click and reloads", async () => {
        vi.mocked(api.fetchProcessingFailures).mockResolvedValue([failure]);
        vi.mocked(api.retryEventProcessing).mockResolvedValue({} as Awaited<ReturnType<typeof api.retryEventProcessing>>);
        renderWidget("routing-failures");
        expect(await screen.findByText("pr.opened")).toHaveAttribute("href", "/events/stream/e-1");
        expect(api.retryEventProcessing).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole("button", { name: "Retry ledger 77" }));
        expect(await screen.findByText("Retry queued for pr.opened.")).toBeInTheDocument();
        expect(api.retryEventProcessing).toHaveBeenCalledWith("e-1", 77);
        await waitFor(() => expect(api.fetchProcessingFailures).toHaveBeenCalledTimes(2));
    });
    it("shows a retry error inline", async () => {
        vi.mocked(api.fetchProcessingFailures).mockResolvedValue([failure]);
        vi.mocked(api.retryEventProcessing).mockRejectedValue(new Error("Retry not allowed"));
        renderWidget("routing-failures");
        fireEvent.click(await screen.findByRole("button", { name: "Retry ledger 77" }));
        expect(await screen.findByText("Retry not allowed")).toBeInTheDocument();
    });
    it("shows the empty state", async () => {
        vi.mocked(api.fetchProcessingFailures).mockResolvedValue([]);
        renderWidget("routing-failures");
        expect(await screen.findByText("No processing failures.")).toBeInTheDocument();
    });
});

describe("EventVolumeWidget", () => {
    it("renders totals and requests daily buckets for long windows", async () => {
        vi.mocked(api.fetchEventVolumeHistogram).mockResolvedValue([
            { bucketStart: "2026-01-01T00:00:00Z", count: 3 },
            { bucketStart: "2026-01-02T00:00:00Z", count: 4 },
        ]);
        renderWidget("event-volume", { since: "30d", eventSourceId: "src-1" });
        expect(await screen.findByTestId("event-volume-total")).toHaveTextContent("7 events (daily)");
        expect(api.fetchEventVolumeHistogram).toHaveBeenCalledWith(expect.any(String), "day", "src-1");
    });
    it("shows the empty state for all-zero buckets", async () => {
        vi.mocked(api.fetchEventVolumeHistogram).mockResolvedValue([{ bucketStart: "2026-01-01T00:00:00Z", count: 0 }]);
        renderWidget("event-volume");
        expect(await screen.findByText("No events received in this window.")).toBeInTheDocument();
        expect(api.fetchEventVolumeHistogram).toHaveBeenCalledWith(expect.any(String), "hour", undefined);
    });
    it("shows an error", async () => {
        vi.mocked(api.fetchEventVolumeHistogram).mockRejectedValue(new Error("x"));
        renderWidget("event-volume");
        expect(await screen.findByText("Error loading data.")).toBeInTheDocument();
    });
});

describe("AiUsageBreakdownWidget", () => {
    it("groups by the configured dimension and shows the excluded note", async () => {
        vi.mocked(api.fetchAiUsageSummary).mockResolvedValue({
            groupBy: "workflow", totalCostUsd: 3, excludedUnscopedCostUsd: 0.5,
            rows: [
                { key: "3", label: "Triage", costUsd: 2, inputTokens: 100, outputTokens: 50, invocations: 4 },
                { key: "", label: "(none)", costUsd: 1, inputTokens: 10, outputTokens: 5, invocations: 1 },
            ],
        });
        renderWidget("ai-usage-breakdown", { groupBy: "workflow", since: "30d", limit: 5 }, ["team=a"]);
        expect(await screen.findByText("Triage")).toHaveAttribute("href", "/components/workflows/3");
        expect(screen.getByText("(none)").closest("a")).toBeNull();
        expect(screen.getByText(/Excludes \$0.50/)).toBeInTheDocument();
        expect(screen.getByText("Total: $3.00")).toBeInTheDocument();
        expect(api.fetchAiUsageSummary).toHaveBeenCalledWith("workflow",
            expect.objectContaining({ labels: ["team=a"], limit: 5 }));
    });
    it("shows the empty state", async () => {
        vi.mocked(api.fetchAiUsageSummary).mockResolvedValue({ groupBy: "model", totalCostUsd: 0, rows: [] });
        renderWidget("ai-usage-breakdown", { groupBy: "model" });
        expect(await screen.findByText("No AI usage data.")).toBeInTheDocument();
    });
});

describe("WorkflowsWidget", () => {
    it("shows published state, schedules and last run with three requests", async () => {
        vi.mocked(api.fetchWorkflowDefinitions).mockResolvedValue(page([
            { id: 3, name: "Triage", currentVersion: 2 },
            { id: 4, name: "Draft one" },
        ] as api.WorkflowDefinition[], 12));
        vi.mocked(api.fetchWorkflowRuns).mockResolvedValue(page([failedRun]));
        vi.mocked(api.fetchWorkflowSchedules).mockResolvedValue([
            { triggerId: 1, workflowDefinitionId: 3, workflowName: "Triage", cron: "0 * * * *", enabled: true },
        ]);
        renderWidget("workflows");
        expect(await screen.findByText("Triage")).toHaveAttribute("href", "/components/workflows/3");
        expect(screen.getByText("v2")).toBeInTheDocument();
        expect(screen.getByText("Draft")).toBeInTheDocument();
        expect(screen.getByText("1 of 1 enabled")).toBeInTheDocument();
        expect(screen.getByText("failed").closest("a")).toHaveAttribute("href", "/runs/11");
        expect(screen.getByText(/Showing 2 of 12/)).toBeInTheDocument();
        expect(api.fetchWorkflowRuns).toHaveBeenCalledTimes(1);
    });
    it("shows the empty and error states", async () => {
        vi.mocked(api.fetchWorkflowDefinitions).mockResolvedValueOnce(page([]));
        vi.mocked(api.fetchWorkflowRuns).mockResolvedValue(page([]));
        vi.mocked(api.fetchWorkflowSchedules).mockResolvedValue([]);
        const { unmount } = renderWidget("workflows");
        expect(await screen.findByText(/No workflows yet/)).toBeInTheDocument();
        unmount();
        vi.mocked(api.fetchWorkflowDefinitions).mockRejectedValueOnce(new Error("x"));
        renderWidget("workflows");
        expect(await screen.findByText("Error loading data.")).toBeInTheDocument();
    });
});
