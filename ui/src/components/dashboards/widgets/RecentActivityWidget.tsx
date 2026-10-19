import { useState, useEffect } from "react";
import { Label } from "@patternfly/react-core";
import { Link } from "react-router-dom";
import { type ActivityLogEntry, fetchActivityLog } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { activityTypePresentation } from "../../../config/activityTypes";
import { formatAge } from "./widgetUtils";

const linkStyle = { marginLeft: "8px", fontSize: "0.8em" };

/** Links from an activity entry to its subject, trace and event when those ids are present. */
function EntryLinks({ entry }: { entry: ActivityLogEntry }) {
    return (
        <>
            {entry.subjectId != null && (
                <Link to={`/subjects/${entry.subjectId}`} style={linkStyle}>subject</Link>
            )}
            {entry.traceId && (
                <Link to={`/logs/traces/${entry.traceId}`} style={linkStyle}>trace</Link>
            )}
            {entry.eventId && (
                <Link to={`/events/stream/${entry.eventId}`} style={linkStyle}>event</Link>
            )}
        </>
    );
}

function RecentActivityWidget({ config, labels, refreshToken }: WidgetProps) {
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
    }, [labels, maxEntries, refreshToken]);

    if (error) return <WidgetError />;

    return (
        <div style={{ overflow: "auto", maxHeight: "100%" }}>
            {entries.map(entry => (
                <div key={entry.id} style={{
                    padding: "6px 8px", borderBottom: "1px solid var(--pf-t--global--border--color--default)",
                    fontSize: "0.85rem",
                }}>
                    <Label isCompact color={activityTypePresentation(entry.entryType).color}
                           title={entry.entryType} style={{ marginRight: "8px" }}>
                        {activityTypePresentation(entry.entryType).label}
                    </Label>
                    <span>{entry.summary}</span>
                    <EntryLinks entry={entry} />
                    <span style={{ float: "right", color: "var(--pf-t--global--color--200)",
                                   fontSize: "0.8em" }}>
                        {formatAge(entry.createdOn)}
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
    description: "Latest workflow, task and subject activity with links to the subject, trace and event.",
    category: "Runs",
    scope: "subject-labels",
    liveEvents: [SSE_EVENTS.activity],
    defaultSize: { w: 4, h: 4 },
    minSize: { w: 3, h: 2 },
    configSchema: [
        { key: "maxEntries", label: "Max Entries", type: "number", default: 15 },
    ],
    component: RecentActivityWidget,
});
