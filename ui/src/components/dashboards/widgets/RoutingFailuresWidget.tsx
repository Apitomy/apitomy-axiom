import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Alert, Button, Label } from "@patternfly/react-core";
import { fetchProcessingFailures, retryEventProcessing, type ProcessingFailure } from "../../../config/api";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { cellStyle, formatAge, WidgetEmpty, WidgetLoading } from "./widgetUtils";

const STATUS_LABELS: Record<string, string> = {
    "failed": "Failed", "exhausted": "Exhausted", "filter_error": "Filter error",
};

function RoutingFailuresWidget({ config, refreshToken }: WidgetProps) {
    const [failures, setFailures] = useState<ProcessingFailure[] | null>(null);
    const [error, setError] = useState(false);
    const [reload, setReload] = useState(0);
    const [busy, setBusy] = useState<number | null>(null);
    const [result, setResult] = useState<{ variant: "success" | "danger"; message: string } | null>(null);
    const maxRows = Math.min(200, Math.max(1, Number(config.maxRows) || 10));

    useEffect(() => {
        let cancelled = false;
        fetchProcessingFailures(maxRows)
            .then(f => { if (!cancelled) { setFailures(f); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxRows, refreshToken, reload]);

    const retry = (f: ProcessingFailure) => {
        setBusy(f.ledgerId);
        setResult(null);
        retryEventProcessing(f.eventId, f.ledgerId)
            .then(() => {
                setResult({ variant: "success", message: `Retry queued for ${f.eventType}.` });
                setReload(r => r + 1);
            })
            .catch((e: unknown) => setResult({
                variant: "danger", message: e instanceof Error ? e.message : "Failed to retry processing",
            }))
            .finally(() => setBusy(null));
    };

    if (error) return <WidgetError />;
    if (!failures) return <WidgetLoading label="Loading processing failures" />;

    return (
        <div>
            {result && <Alert isInline isPlain variant={result.variant} title={result.message} />}
            {failures.length === 0 ? <WidgetEmpty>No processing failures.</WidgetEmpty> : (
                <table style={{ width: "100%", borderCollapse: "collapse" }}>
                    <thead>
                        <tr>
                            <th style={cellStyle}>Event</th>
                            <th style={cellStyle}>Subscription</th>
                            <th style={cellStyle}>Status</th>
                            <th style={cellStyle}>When</th>
                            <th style={cellStyle} />
                        </tr>
                    </thead>
                    <tbody>
                        {failures.map(f => (
                            <tr key={f.ledgerId}>
                                <td style={cellStyle}>
                                    <Link to={`/events/stream/${encodeURIComponent(f.eventId)}`}>{f.eventType}</Link>
                                    {f.errorMessage && (
                                        <div style={{ fontSize: "0.75rem" }} title={f.errorMessage}>
                                            {f.errorMessage.length > 80 ? `${f.errorMessage.slice(0, 80)}...` : f.errorMessage}
                                        </div>
                                    )}
                                </td>
                                <td style={cellStyle}>
                                    <Link to={`/events/subscriptions/${encodeURIComponent(f.subscriptionId)}`}>
                                        {f.subscriptionName || `#${f.subscriptionId}`}
                                    </Link>
                                </td>
                                <td style={cellStyle}>
                                    <Label isCompact color={f.status === "failed" ? "orange" : "red"}>
                                        {STATUS_LABELS[f.status] ?? f.status}
                                    </Label>
                                    <div style={{ fontSize: "0.75rem" }}>{f.attemptCount} attempts</div>
                                </td>
                                <td style={cellStyle} title={f.updatedAt}>{formatAge(f.updatedAt)}</td>
                                <td style={cellStyle}>
                                    <Button variant="secondary" size="sm" isDisabled={busy !== null}
                                            isLoading={busy === f.ledgerId} onClick={() => retry(f)}
                                            aria-label={`Retry ledger ${f.ledgerId}`}>
                                        Retry
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
    type: "routing-failures",
    name: "Processing Failures",
    description: "Recent failed, exhausted and filter-error event processing entries, newest first, with a retry "
        + "action. Not filtered by dashboard labels.",
    category: "Events",
    scope: "global",
    liveEvents: [SSE_EVENTS.streamProcessing],
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10, min: 1, max: 200 },
    ],
    component: RoutingFailuresWidget,
});
