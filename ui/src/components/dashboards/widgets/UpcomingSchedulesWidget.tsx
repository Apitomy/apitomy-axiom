import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { fetchWorkflowSchedules, type WorkflowScheduleSummary } from "../../../config/api";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { cellStyle, formatTime, WidgetEmpty, WidgetLoading } from "./widgetUtils";

const OUTCOME_COLORS: Record<string, "green" | "grey" | "red"> = {
    "started": "green", "skipped-duplicate": "grey", "failed": "red",
};

function UpcomingSchedulesWidget({ config, refreshToken }: WidgetProps) {
    const [schedules, setSchedules] = useState<WorkflowScheduleSummary[] | null>(null);
    const [error, setError] = useState(false);
    const maxRows = Math.min(500, Math.max(1, Number(config.maxRows) || 10));
    const showDisabled = config.showDisabled === true;

    useEffect(() => {
        let cancelled = false;
        fetchWorkflowSchedules(maxRows)
            .then(s => { if (!cancelled) { setSchedules(s); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxRows, refreshToken]);

    if (error) return <WidgetError />;
    if (!schedules) return <WidgetLoading label="Loading schedules" />;
    const rows = showDisabled ? schedules : schedules.filter(s => s.enabled);
    if (rows.length === 0) return <WidgetEmpty>No scheduled workflows.</WidgetEmpty>;

    return (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
                <tr>
                    <th style={cellStyle}>Workflow</th>
                    <th style={cellStyle}>Next</th>
                    <th style={cellStyle}>Last</th>
                </tr>
            </thead>
            <tbody>
                {rows.map(s => (
                    <tr key={s.triggerId}>
                        <td style={cellStyle}>
                            <Link to={`/components/workflows/${s.workflowDefinitionId}`}>{s.workflowName}</Link>
                            <div style={{ fontSize: "0.75rem" }}>
                                <code>{s.cron}</code>
                                {s.subjectId != null && <> for <Link to={`/subjects/${s.subjectId}`}>
                                    {s.subjectName || `#${s.subjectId}`}</Link></>}
                                {!s.enabled && <> <Label isCompact color="grey">Disabled</Label></>}
                            </div>
                        </td>
                        <td style={cellStyle}>{s.enabled ? formatTime(s.nextFireAt) : "-"}</td>
                        <td style={cellStyle}>
                            {formatTime(s.lastFiredAt)}
                            {s.lastOutcome && (
                                <div>
                                    {s.lastRunId != null
                                        ? <Link to={`/runs/${s.lastRunId}`}>
                                            <Label isCompact color={OUTCOME_COLORS[s.lastOutcome] ?? "grey"}>
                                                {s.lastOutcome}</Label></Link>
                                        : <Label isCompact color={OUTCOME_COLORS[s.lastOutcome] ?? "grey"}>
                                            {s.lastOutcome}</Label>}
                                </div>
                            )}
                        </td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}

registerWidget({
    type: "upcoming-schedules",
    name: "Upcoming Schedules",
    description: "Scheduled workflow triggers ordered by next firing time, with the last firing outcome. "
        + "Not filtered by dashboard labels.",
    category: "Runs",
    scope: "global",
    liveEvents: [SSE_EVENTS.workflowUpdated],
    defaultSize: { w: 5, h: 3 },
    minSize: { w: 3, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10, min: 1, max: 500 },
        { key: "showDisabled", label: "Show disabled schedules", type: "toggle", default: false },
    ],
    component: UpcomingSchedulesWidget,
});
