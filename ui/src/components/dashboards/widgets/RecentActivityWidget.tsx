import { useState, useEffect } from "react";
import { Label } from "@patternfly/react-core";
import { type ActivityLogEntry, fetchActivityLog } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

const TYPE_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "purple"> = {
    decision: "blue", result: "green", update: "orange", error: "grey", message: "purple",
};

function RecentActivityWidget({ config, labels }: WidgetProps) {
    const [entries, setEntries] = useState<ActivityLogEntry[]>([]);
    const [error, setError] = useState(false);
    const maxEntries = Number(config.maxEntries) || 15;

    useEffect(() => {
        let cancelled = false;
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;
        fetchActivityLog(1, maxEntries, undefined, undefined, undefined, undefined, labelsParam)
            .then(result => { if (!cancelled) setEntries(result.items); })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, maxEntries]);

    if (error) return <WidgetError />;

    const formatTime = (dateStr: string) => {
        const d = new Date(dateStr);
        const now = new Date();
        const diffMs = now.getTime() - d.getTime();
        const diffMin = Math.floor(diffMs / 60000);
        if (diffMin < 60) return `${diffMin}m ago`;
        const diffHr = Math.floor(diffMin / 60);
        if (diffHr < 24) return `${diffHr}h ago`;
        return `${Math.floor(diffHr / 24)}d ago`;
    };

    return (
        <div style={{ overflow: "auto", maxHeight: "100%" }}>
            {entries.map(entry => (
                <div key={entry.id} style={{
                    padding: "6px 8px", borderBottom: "1px solid var(--pf-t--global--border--color--default)",
                    fontSize: "0.85rem",
                }}>
                    <Label isCompact color={TYPE_COLORS[entry.entryType] || "grey"}
                           style={{ marginRight: "8px" }}>
                        {entry.entryType}
                    </Label>
                    <span>{entry.summary}</span>
                    <span style={{ float: "right", color: "var(--pf-t--global--color--200)",
                                   fontSize: "0.8em" }}>
                        {formatTime(entry.createdOn)}
                    </span>
                </div>
            ))}
            {entries.length === 0 && (
                <p style={{ padding: "16px", textAlign: "center" }}>No recent activity.</p>
            )}
        </div>
    );
}

registerWidget({
    type: "recent-activity",
    name: "Recent Activity",
    description: "Time-ordered feed of activity log entries with type badges.",
    category: "Operations",
    defaultSize: { w: 4, h: 4 },
    minSize: { w: 3, h: 2 },
    configSchema: [
        { key: "maxEntries", label: "Max Entries", type: "number", default: 15 },
    ],
    component: RecentActivityWidget,
});
