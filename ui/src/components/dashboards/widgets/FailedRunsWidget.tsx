import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Alert, Button } from "@patternfly/react-core";
import { fetchWorkflowRunStatusSummary, rerunWorkflowRun, type WorkflowRunSummary } from "../../../config/api";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { cellStyle, formatAge, sinceField, sinceIso, WidgetEmpty, WidgetLoading } from "./widgetUtils";

interface ActionResult {
    variant: "success" | "danger";
    message: string;
    newRunId?: number;
}

function FailedRunsWidget({ config, labels, refreshToken }: WidgetProps) {
    const [runs, setRuns] = useState<WorkflowRunSummary[] | null>(null);
    const [error, setError] = useState(false);
    const [reload, setReload] = useState(0);
    const [busy, setBusy] = useState<number | null>(null);
    const [result, setResult] = useState<ActionResult | null>(null);
    const since = String(config.since || "7d");
    const maxRows = Math.min(50, Math.max(1, Number(config.maxRows) || 10));
    const labelKey = labels.join(",");

    useEffect(() => {
        let cancelled = false;
        fetchWorkflowRunStatusSummary({ labels, since: sinceIso(since), failedLimit: maxRows })
            .then(s => { if (!cancelled) { setRuns(s.recentFailed); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labelKey, since, maxRows, refreshToken, reload]);

    const rerun = (runId: number) => {
        setBusy(runId);
        setResult(null);
        rerunWorkflowRun(runId)
            .then(info => {
                const newRunId = info?.id;
                setResult({ variant: "success", message: `Run #${runId} re-run started.`, newRunId });
                setReload(r => r + 1);
            })
            .catch((e: unknown) => setResult({
                variant: "danger", message: e instanceof Error ? e.message : "Failed to re-run workflow run",
            }))
            .finally(() => setBusy(null));
    };

    if (error) return <WidgetError />;
    if (!runs) return <WidgetLoading label="Loading failed runs" />;

    return (
        <div>
            {result && (
                <Alert isInline isPlain variant={result.variant} title={result.message}
                       actionLinks={result.newRunId != null
                           ? <Link to={`/runs/${result.newRunId}`}>View new run</Link> : undefined} />
            )}
            {runs.length === 0 ? <WidgetEmpty>No failed runs in this window.</WidgetEmpty> : (
                <table style={{ width: "100%", borderCollapse: "collapse" }}>
                    <thead>
                        <tr>
                            <th style={cellStyle}>Run</th>
                            <th style={cellStyle}>Subject</th>
                            <th style={cellStyle}>Failed</th>
                            <th style={cellStyle} />
                        </tr>
                    </thead>
                    <tbody>
                        {runs.map(r => (
                            <tr key={r.runId}>
                                <td style={cellStyle}>
                                    <Link to={`/runs/${r.runId}`}>
                                        {r.title || r.definitionName || `Run #${r.runId}`}
                                    </Link>
                                    {r.title && r.definitionName && (
                                        <div style={{ fontSize: "0.75rem" }}>{r.definitionName}</div>
                                    )}
                                </td>
                                <td style={cellStyle}>
                                    {r.subjectId != null
                                        ? <Link to={`/subjects/${r.subjectId}`}>{r.subjectName || `#${r.subjectId}`}</Link>
                                        : "-"}
                                </td>
                                <td style={cellStyle} title={r.completedOn}>
                                    {formatAge(r.completedOn ?? r.startedOn)}
                                </td>
                                <td style={cellStyle}>
                                    <Button variant="secondary" size="sm" isDisabled={busy !== null}
                                            isLoading={busy === r.runId} onClick={() => rerun(r.runId)}
                                            aria-label={`Re-run run ${r.runId}`}>
                                        Re-run
                                    </Button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
}

registerWidget({
    type: "failed-runs",
    name: "Recent Failed Runs",
    description: "Most recently failed workflow runs in the selected time window, with a re-run action.",
    category: "Runs",
    scope: "subject-labels",
    liveEvents: [SSE_EVENTS.workflowUpdated],
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        sinceField("7d"),
        { key: "maxRows", label: "Max Rows", type: "number", default: 10, min: 1, max: 50 },
    ],
    component: FailedRunsWidget,
});
