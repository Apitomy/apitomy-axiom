import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { fetchSubscriptionProcessingSummary, type SubscriptionProcessingSummary } from "../../../config/api";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { cellStyle, formatAge, sinceField, sinceIso, WidgetEmpty, WidgetLoading } from "./widgetUtils";

/**
 * Total failures for a subscription (failed + exhausted + filter error).
 *
 * @param s the summary
 * @returns the failure count
 */
export function failureCount(s: SubscriptionProcessingSummary): number {
    return s.counts.failed + s.counts.exhausted + s.counts.filterError;
}

function SubscriptionProcessingWidget({ config, refreshToken }: WidgetProps) {
    const [rows, setRows] = useState<SubscriptionProcessingSummary[] | null>(null);
    const [error, setError] = useState(false);
    const since = String(config.since || "24h");
    const hideIdle = config.hideIdle !== false;

    useEffect(() => {
        let cancelled = false;
        fetchSubscriptionProcessingSummary(sinceIso(since))
            .then(r => { if (!cancelled) { setRows(r); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [since, refreshToken]);

    if (error) return <WidgetError />;
    if (!rows) return <WidgetLoading label="Loading subscription processing" />;

    const total = (s: SubscriptionProcessingSummary) =>
        Object.values(s.counts).reduce((a, b) => a + b, 0);
    const visible = (hideIdle ? rows.filter(s => total(s) > 0) : [...rows])
        .sort((a, b) => failureCount(b) - failureCount(a) || total(b) - total(a));
    if (visible.length === 0) return <WidgetEmpty>No events processed in this window.</WidgetEmpty>;

    return (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
                <tr>
                    <th style={cellStyle}>Subscription</th>
                    <th style={cellStyle}>Completed</th>
                    <th style={cellStyle}>Pending</th>
                    <th style={cellStyle}>Skipped</th>
                    <th style={cellStyle}>Failed</th>
                </tr>
            </thead>
            <tbody>
                {visible.map(s => {
                    const failures = failureCount(s);
                    return (
                        <tr key={s.subscriptionId}>
                            <td style={cellStyle}>
                                <Link to={`/events/subscriptions/${s.subscriptionId}`}>{s.name}</Link>
                                {!s.enabled && <> <Label isCompact color="grey">Disabled</Label></>}
                                {s.lastError && (
                                    <div style={{ fontSize: "0.75rem" }} title={s.lastError}>
                                        Last error {formatAge(s.lastErrorAt)} ago
                                    </div>
                                )}
                            </td>
                            <td style={cellStyle}>{s.counts.completed}</td>
                            <td style={cellStyle}>{s.counts.pending}</td>
                            <td style={cellStyle}>{s.counts.skipped}</td>
                            <td style={cellStyle}>
                                {failures > 0
                                    ? <Label isCompact color="red"
                                             title={`failed ${s.counts.failed}, exhausted ${s.counts.exhausted}, `
                                                 + `filter error ${s.counts.filterError}`}>{failures}</Label>
                                    : 0}
                            </td>
                        </tr>
                    );
                })}
            </tbody>
        </table>
    );
}

registerWidget({
    type: "subscription-processing",
    name: "Subscription Processing",
    description: "Event processing outcomes per subscription in the selected time window. Not filtered by "
        + "dashboard labels.",
    category: "Events",
    scope: "global",
    liveEvents: [SSE_EVENTS.streamProcessing],
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        sinceField("24h"),
        { key: "hideIdle", label: "Hide subscriptions with no activity", type: "toggle", default: true },
    ],
    component: SubscriptionProcessingWidget,
});
