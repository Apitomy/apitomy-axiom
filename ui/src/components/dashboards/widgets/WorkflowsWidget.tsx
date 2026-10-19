import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import {
    fetchWorkflowDefinitions, fetchWorkflowRuns, fetchWorkflowSchedules,
    type WorkflowDefinition, type WorkflowRunSummary, type WorkflowScheduleSummary,
} from "../../../config/api";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { cellStyle, formatAge, WidgetEmpty, WidgetLoading } from "./widgetUtils";

/** Number of most recent runs scanned (one request) to find each workflow's last run. */
export const RECENT_RUNS_SCAN = 200;

const RUN_STATUS_COLORS: Record<string, "blue" | "orange" | "green" | "red" | "grey"> = {
    running: "blue", waiting: "orange", completed: "green", failed: "red", cancelled: "grey",
};

interface WorkflowRow {
    definition: WorkflowDefinition;
    lastRun?: WorkflowRunSummary;
    enabledSchedules: number;
    totalSchedules: number;
}

/**
 * Joins definitions with their most recent run and schedule counts (no per-definition requests).
 *
 * @param definitions the workflow definitions
 * @param runs recent runs, newest first
 * @param schedules all schedule triggers
 * @returns one row per definition
 */
export function buildWorkflowRows(definitions: WorkflowDefinition[], runs: WorkflowRunSummary[],
                                  schedules: WorkflowScheduleSummary[]): WorkflowRow[] {
    const lastRun = new Map<number, WorkflowRunSummary>();
    runs.forEach(r => { if (!lastRun.has(r.definitionId)) lastRun.set(r.definitionId, r); });
    return definitions.map(d => {
        const own = schedules.filter(s => s.workflowDefinitionId === d.id);
        return {
            definition: d,
            lastRun: lastRun.get(d.id),
            enabledSchedules: own.filter(s => s.enabled).length,
            totalSchedules: own.length,
        };
    });
}

function WorkflowsWidget({ config, refreshToken }: WidgetProps) {
    const [rows, setRows] = useState<WorkflowRow[] | null>(null);
    const [totalCount, setTotalCount] = useState(0);
    const [error, setError] = useState(false);
    const maxRows = Math.min(100, Math.max(1, Number(config.maxRows) || 10));

    useEffect(() => {
        let cancelled = false;
        Promise.all([
            fetchWorkflowDefinitions(1, maxRows),
            fetchWorkflowRuns(1, RECENT_RUNS_SCAN),
            // Schedules are optional decoration: tolerate failure.
            fetchWorkflowSchedules(500).catch(() => [] as WorkflowScheduleSummary[]),
        ])
            .then(([defs, runs, schedules]) => {
                if (cancelled) return;
                setRows(buildWorkflowRows(defs.items, runs.items, schedules));
                setTotalCount(defs.totalCount);
                setError(false);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxRows, refreshToken]);

    if (error) return <WidgetError />;
    if (!rows) return <WidgetLoading label="Loading workflows" />;
    if (rows.length === 0) {
        return <WidgetEmpty>No workflows yet. <Link to="/components/workflows">Create one</Link></WidgetEmpty>;
    }

    return (
        <div>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
                <thead>
                    <tr>
                        <th style={cellStyle}>Workflow</th>
                        <th style={cellStyle}>Published</th>
                        <th style={cellStyle}>Schedules</th>
                        <th style={cellStyle}>Last run</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.map(({ definition: d, lastRun, enabledSchedules, totalSchedules }) => (
                        <tr key={d.id}>
                            <td style={cellStyle}>
                                <Link to={`/components/workflows/${d.id}`}>{d.name}</Link>
                            </td>
                            <td style={cellStyle}>
                                {d.currentVersion != null
                                    ? <Label isCompact color="green">v{d.currentVersion}</Label>
                                    : <Label isCompact color="grey">Draft</Label>}
                            </td>
                            <td style={cellStyle}>
                                {totalSchedules === 0 ? "-" : `${enabledSchedules} of ${totalSchedules} enabled`}
                            </td>
                            <td style={cellStyle}>
                                {lastRun ? (
                                    <Link to={`/runs/${lastRun.runId}`} title={lastRun.startedOn}>
                                        <Label isCompact color={RUN_STATUS_COLORS[lastRun.status] ?? "grey"}>
                                            {lastRun.status}
                                        </Label>{" "}
                                        {formatAge(lastRun.startedOn)}
                                    </Link>
                                ) : <span title={`Not among the ${RECENT_RUNS_SCAN} most recent runs`}>-</span>}
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            {totalCount > rows.length && (
                <div style={{ fontSize: "0.8rem", padding: "4px" }}>
                    Showing {rows.length} of {totalCount}. <Link to="/components/workflows">View all</Link>
                </div>
            )}
        </div>
    );
}

registerWidget({
    type: "workflows",
    name: "Workflows",
    description: "Workflow definitions with their published version, schedule triggers and most recent run. "
        + "Not filtered by dashboard labels.",
    category: "Runs",
    scope: "global",
    liveEvents: [SSE_EVENTS.workflowUpdated],
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10, min: 1, max: 100 },
    ],
    component: WorkflowsWidget,
});
