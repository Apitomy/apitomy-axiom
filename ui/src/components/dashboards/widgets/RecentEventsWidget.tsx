import { useState, useEffect } from "react";
import { Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type AxiomEvent, fetchEvents } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

function RecentEventsWidget({ config, labels }: WidgetProps) {
    const [events, setEvents] = useState<AxiomEvent[]>([]);
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 10;

    useEffect(() => {
        let cancelled = false;
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;
        fetchEvents(1, maxRows, undefined, undefined, undefined, labelsParam)
            .then(result => { if (!cancelled) setEvents(result.items); })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, maxRows]);

    if (error) return <WidgetError />;

    const formatTime = (dateStr: string) => {
        return new Date(dateStr).toLocaleDateString(undefined, {
            month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
        });
    };

    return (
        <Table aria-label="Recent Events" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Source</Th><Th>Type</Th><Th>Repository</Th><Th>Time</Th>
            </Tr></Thead>
            <Tbody>
                {events.map(evt => (
                    <Tr key={evt.id}>
                        <Td>
                            <Label isCompact color={evt.source === "github" ? "blue" : "purple"}>
                                {evt.source}
                            </Label>
                        </Td>
                        <Td>{evt.eventType}</Td>
                        <Td>{evt.repository || "—"}</Td>
                        <Td>{formatTime(evt.receivedAt)}</Td>
                    </Tr>
                ))}
                {events.length === 0 && (
                    <Tr><Td colSpan={4}>No recent events.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "recent-events",
    name: "Recent Events",
    description: "Latest events from GitHub/Jira with source, type, and timestamp.",
    category: "Operations",
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10 },
    ],
    component: RecentEventsWidget,
});
