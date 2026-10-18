import { useEffect, useState } from "react";
import { Chart, ChartAxis, ChartBar, ChartVoronoiContainer } from "@patternfly/react-charts/victory";
import { fetchEventVolumeHistogram, type EventVolumeBucket } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { SINCE_WINDOWS, sinceField, sinceIso, WidgetEmpty, WidgetLoading } from "./widgetUtils";

/**
 * Picks the bucket size for a window so the server's 400-bucket cap is never exceeded: hourly up to 7 days,
 * daily beyond.
 *
 * @param window the window key
 * @returns the bucket size
 */
export function bucketFor(window: string): "hour" | "day" {
    return (SINCE_WINDOWS[window] ?? 1) <= 7 ? "hour" : "day";
}

/** How often the event volume chart is reloaded (5 minutes). */
export const EVENT_VOLUME_POLL_MS = 5 * 60 * 1000;

function EventVolumeWidget({ config, refreshToken }: WidgetProps) {
    const [buckets, setBuckets] = useState<EventVolumeBucket[] | null>(null);
    const [error, setError] = useState(false);
    const since = String(config.since || "24h");
    const eventSourceId = typeof config.eventSourceId === "string" && config.eventSourceId
        ? config.eventSourceId : undefined;
    const bucket = bucketFor(since);

    useEffect(() => {
        let cancelled = false;
        const load = () => {
            fetchEventVolumeHistogram(sinceIso(since), bucket, eventSourceId)
                .then(b => { if (!cancelled) { setBuckets(b); setError(false); } })
                .catch(() => { if (!cancelled) setError(true); });
        };
        load();
        const timer = setInterval(load, EVENT_VOLUME_POLL_MS);
        return () => { cancelled = true; clearInterval(timer); };
    }, [since, bucket, eventSourceId, refreshToken]);

    if (error) return <WidgetError />;
    if (!buckets) return <WidgetLoading label="Loading event volume" />;
    const total = buckets.reduce((sum, b) => sum + b.count, 0);
    if (total === 0) return <WidgetEmpty>No events received in this window.</WidgetEmpty>;

    const fmt = (iso: string) => {
        const d = new Date(iso);
        return bucket === "hour"
            ? d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit" })
            : d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
    };
    const data = buckets.map((b, i) => ({ x: i, y: b.count, label: `${fmt(b.bucketStart)}: ${b.count}` }));
    const tickStep = Math.max(1, Math.ceil(buckets.length / 6));
    const tickValues = data.filter((_, i) => i % tickStep === 0).map(d => d.x);

    return (
        <div style={{ height: "100%", minHeight: "150px" }}>
            <div style={{ fontSize: "0.8rem", padding: "2px 4px" }} data-testid="event-volume-total">
                {total} events ({bucket === "hour" ? "hourly" : "daily"})
            </div>
            <Chart
                containerComponent={<ChartVoronoiContainer labels={({ datum }: { datum: { label: string } }) => datum.label} />}
                domainPadding={{ x: [10, 10] }}
                padding={{ bottom: 40, left: 50, right: 20, top: 10 }}
                height={200}
            >
                <ChartAxis tickValues={tickValues}
                           tickFormat={(t: number) => buckets[t] ? fmt(buckets[t].bucketStart) : ""} />
                <ChartAxis dependentAxis showGrid />
                <ChartBar data={data} />
            </Chart>
        </div>
    );
}

registerWidget({
    type: "event-volume",
    name: "Event Volume",
    description: "Number of stream events received over time (hourly up to 7 days, daily beyond), optionally "
        + "for one event source. Reloads every 5 minutes. Not filtered by dashboard labels.",
    category: "Events",
    scope: "global",
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 3 },
    configSchema: [
        sinceField("24h"),
        { key: "eventSourceId", label: "Event Source", type: "eventSource", default: null,
          helperText: "Leave empty for all event sources." },
    ],
    component: EventVolumeWidget,
});
