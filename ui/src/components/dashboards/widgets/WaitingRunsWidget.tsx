import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { fetchWaitingWorkflowRuns, type WaitingWorkflowRun } from "../../../config/api";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { cellStyle, formatAge, formatTime, WidgetEmpty, WidgetLoading } from "./widgetUtils";

const KIND_LABELS: Record<WaitingWorkflowRun["waitKind"], { label: string; color: "blue" | "purple" | "orange" }> = {
    "wait": { label: "Timer", color: "blue" },
    "receive-event": { label: "Event", color: "purple" },
    "human-task": { label: "Human task", color: "orange" },
};

/**
 * Describes what a waiting run is waiting for.
 *
 * @param run the waiting run
 * @returns a short description
 */
export function waitingFor(run: WaitingWorkflowRun): string {
    if (run.waitKind === "receive-event") {
        const key = run.correlationKey ? ` (${run.correlationKey})` : "";
        return `${run.eventType ?? "event"}${key}`;
    }
    if (run.waitKind === "wait") return run.dueAt ? `until ${formatTime(run.dueAt)}` : "timer";
    return "input";
}

function WaitingRunsWidget({ config, labels, refreshToken }: WidgetProps) {
    const [runs, setRuns] = useState<WaitingWorkflowRun[] | null>(null);
    const [error, setError] = useState(false);
    const maxRows = Math.min(200, Math.max(1, Number(config.maxRows) || 10));
    const labelKey = labels.join(",");

    useEffect(() => {
        let cancelled = false;
        fetchWaitingWorkflowRuns(labels, maxRows)
            .then(r => { if (!cancelled) { setRuns(r); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labelKey, maxRows, refreshToken]);

    if (error) return <WidgetError />;
    if (!runs) return <WidgetLoading label="Loading waiting runs" />;
    if (runs.length === 0) return <WidgetEmpty>No runs are waiting.</WidgetEmpty>;

    return (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
                <tr>
                    <th style={cellStyle}>Run</th>
                    <th style={cellStyle}>Subject</th>
                    <th style={cellStyle}>Waiting for</th>
                    <th style={cellStyle}>Since</th>
                </tr>
            </thead>
            <tbody>
                {runs.map(r => {
                    const kind = KIND_LABELS[r.waitKind] ?? { label: r.waitKind, color: "blue" as const };
                    return (
                        <tr key={`${r.runId}-${r.nodeId}`}>
                            <td style={cellStyle}>
                                <Link to={`/runs/${r.runId}`}>{r.runTitle || r.workflowName || `Run #${r.runId}`}</Link>
                                {r.runTitle && <div style={{ fontSize: "0.75rem" }}>{r.workflowName}</div>}
                            </td>
                            <td style={cellStyle}>
                                {r.subjectId != null
                                    ? <Link to={`/subjects/${r.subjectId}`}>{r.subjectName || `#${r.subjectId}`}</Link>
                                    : "-"}
                            </td>
                            <td style={cellStyle}>
                                <Label color={kind.color} isCompact>{kind.label}</Label>{" "}
                                {r.waitKind === "human-task" && r.taskId != null
                                    ? <Link to={`/inbox?task=${r.taskId}`}>open task</Link>
                                    : waitingFor(r)}
                            </td>
                            <td style={cellStyle} title={r.waitingSince}>{formatAge(r.waitingSince)}</td>
                        </tr>
                    );
                })}
            </tbody>
        </table>
    );
}

registerWidget({
    type: "waiting-runs",
    name: "Waiting Runs",
    description: "Workflow runs waiting on a timer, an event or human input, longest waiting first.",
    category: "Runs",
    scope: "subject-labels",
    liveEvents: [SSE_EVENTS.workflowUpdated, SSE_EVENTS.inboxUpdated],
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10, min: 1, max: 200 },
    ],
    component: WaitingRunsWidget,
});
