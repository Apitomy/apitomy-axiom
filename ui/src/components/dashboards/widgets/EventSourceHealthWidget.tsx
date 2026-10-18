import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { fetchEventSourceHealth, type EventSourceHealth } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { cellStyle, formatAge, WidgetEmpty, WidgetLoading } from "./widgetUtils";

/** How often the widget re-fetches event source health (no SSE event exists for polls). */
export const EVENT_SOURCE_HEALTH_POLL_MS = 60000;

const STATE_COLORS: Record<EventSourceHealth["state"], "green" | "red" | "blue" | "grey" | "orange"> = {
    "healthy": "green", "failing": "red", "baseline": "blue", "disabled": "grey", "never-polled": "orange",
};

/** Sort order: problems first. */
const STATE_ORDER: Record<string, number> = {
    "failing": 0, "never-polled": 1, "baseline": 2, "healthy": 3, "disabled": 4,
};

function EventSourceHealthWidget({ refreshToken }: WidgetProps) {
    const [sources, setSources] = useState<EventSourceHealth[] | null>(null);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        const load = () => {
            fetchEventSourceHealth()
                .then(s => { if (!cancelled) { setSources(s); setError(false); } })
                .catch(() => { if (!cancelled) setError(true); });
        };
        load();
        const timer = setInterval(load, EVENT_SOURCE_HEALTH_POLL_MS);
        return () => { cancelled = true; clearInterval(timer); };
    }, [refreshToken]);

    if (error) return <WidgetError />;
    if (!sources) return <WidgetLoading label="Loading event source health" />;
    if (sources.length === 0) {
        return <WidgetEmpty>No event sources. <Link to="/events/sources">Add one</Link></WidgetEmpty>;
    }

    const sorted = [...sources].sort((a, b) =>
        (STATE_ORDER[a.state] ?? 9) - (STATE_ORDER[b.state] ?? 9) || a.name.localeCompare(b.name));

    return (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
                <tr>
                    <th style={cellStyle}>Source</th>
                    <th style={cellStyle}>State</th>
                    <th style={cellStyle}>Last success</th>
                    <th style={cellStyle}>Events (24h)</th>
                </tr>
            </thead>
            <tbody>
                {sorted.map(s => (
                    <tr key={s.id}>
                        <td style={cellStyle}>
                            <Link to={`/events/sources/${encodeURIComponent(s.id)}`}>{s.name}</Link>
                            <div style={{ fontSize: "0.75rem" }}>{s.type}</div>
                        </td>
                        <td style={cellStyle}>
                            <Label isCompact color={STATE_COLORS[s.state] ?? "grey"}>{s.state}</Label>
                            {s.consecutiveFailures > 0 && (
                                <div style={{ fontSize: "0.75rem" }}>{s.consecutiveFailures} consecutive failures</div>
                            )}
                            {s.lastError && (
                                <div style={{ fontSize: "0.75rem", color: "var(--pf-t--global--text--color--status--danger--default)" }}
                                     title={s.lastError}>
                                    {s.lastError.length > 80 ? `${s.lastError.slice(0, 80)}...` : s.lastError}
                                </div>
                            )}
                        </td>
                        <td style={cellStyle} title={s.lastSuccessAt}>
                            {s.lastSuccessAt ? `${formatAge(s.lastSuccessAt)} ago` : "-"}
                        </td>
                        <td style={cellStyle}>{s.eventsLast24h}</td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}

registerWidget({
    type: "event-source-health",
    name: "Event Source Health",
    description: "Polling health of every event source: state, last success, last error and events received in "
        + "the last 24 hours. Refreshes every minute. Not filtered by dashboard labels.",
    category: "Events",
    scope: "global",
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    component: EventSourceHealthWidget,
});
