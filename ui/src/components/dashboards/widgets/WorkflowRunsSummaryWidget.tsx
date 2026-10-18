import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { fetchWorkflowRunStatusSummary, type WorkflowRunStatusCounts } from "../../../config/api";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { sinceField, sinceIso, WidgetLoading } from "./widgetUtils";

const STATUSES: { key: keyof WorkflowRunStatusCounts; label: string;
                  color: "blue" | "orange" | "green" | "red" | "grey" }[] = [
    { key: "running", label: "Running", color: "blue" },
    { key: "waiting", label: "Waiting", color: "orange" },
    { key: "completed", label: "Completed", color: "green" },
    { key: "failed", label: "Failed", color: "red" },
    { key: "cancelled", label: "Cancelled", color: "grey" },
];

function WorkflowRunsSummaryWidget({ config, labels, refreshToken }: WidgetProps) {
    const [counts, setCounts] = useState<WorkflowRunStatusCounts | null>(null);
    const [error, setError] = useState(false);
    const since = String(config.since || "24h");
    const labelKey = labels.join(",");

    useEffect(() => {
        let cancelled = false;
        fetchWorkflowRunStatusSummary({ labels, since: sinceIso(since), failedLimit: 1 })
            .then(s => { if (!cancelled) { setCounts(s.counts); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labelKey, since, refreshToken]);

    if (error) return <WidgetError />;
    if (!counts) return <WidgetLoading label="Loading run summary" />;

    return (
        <div style={{ padding: "8px" }}>
            <div style={{ display: "flex", flexWrap: "wrap", gap: "12px", justifyContent: "space-around" }}>
                {STATUSES.map(s => (
                    <Link key={s.key} to="/runs" style={{ textAlign: "center", textDecoration: "none" }}
                          data-testid={`runs-${s.key}`}>
                        <div style={{ fontSize: "1.6rem", fontWeight: 600 }}>{counts[s.key]}</div>
                        <Label color={s.color} isCompact>{s.label}</Label>
                    </Link>
                ))}
            </div>
            <div style={{ fontSize: "0.75rem", color: "var(--pf-t--global--text--color--subtle)",
                          textAlign: "center", marginTop: "8px" }}>
                Running and waiting are current; other counts cover the selected window.
            </div>
        </div>
    );
}

registerWidget({
    type: "workflow-runs-summary",
    name: "Runs by Status",
    description: "Counts of workflow runs per status. Running and waiting are current; completed, failed and "
        + "cancelled cover the selected time window.",
    category: "Runs",
    scope: "subject-labels",
    liveEvents: [SSE_EVENTS.workflowUpdated],
    defaultSize: { w: 4, h: 2 },
    minSize: { w: 3, h: 2 },
    configSchema: [sinceField("24h")],
    component: WorkflowRunsSummaryWidget,
});
