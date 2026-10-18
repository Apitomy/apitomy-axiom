import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type StreamEvent, type StreamEventProcessingStates, fetchStreamEvents } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { SSE_EVENTS } from "../../../config/sseEvents";

type LabelColor = "blue" | "purple" | "teal" | "orange" | "yellow" | "green" | "red" | "grey";

const SOURCE_COLORS: LabelColor[] = ["blue", "purple", "teal", "orange", "yellow"];

/**
 * Picks a stable label colour for an event source type, so any source type gets a consistent colour.
 *
 * @param source the event source type, for example "github"
 * @returns a label colour derived from the type name
 */
export function sourceColor(source: string): LabelColor {
    let hash = 0;
    for (let i = 0; i < source.length; i++) hash = (hash * 31 + source.charCodeAt(i)) | 0;
    return SOURCE_COLORS[Math.abs(hash) % SOURCE_COLORS.length];
}

/**
 * Summarises an event's processing ledger states into one indicator.
 *
 * @param states per-state ledger counts, absent when no subscription matched
 * @returns the label text and colour
 */
export function processingIndicator(states?: StreamEventProcessingStates): { text: string; color: LabelColor } {
    if (!states) return { text: "no match", color: "grey" };
    const failed = states.failed + states.exhausted + states.filterError;
    if (failed > 0) return { text: `${failed} failed`, color: "red" };
    if (states.pending > 0) return { text: `${states.pending} pending`, color: "blue" };
    if (states.completed > 0) return { text: "processed", color: "green" };
    return { text: "skipped", color: "grey" };
}

/** Shortens a long event reference for display; the full value is kept in the cell title. */
export function shortRef(ref: string | undefined, max = 24): string {
    if (!ref) return "-";
    return ref.length > max ? `...${ref.slice(ref.length - (max - 3))}` : ref;
}

function receivedTime(evt: StreamEvent): number {
    return new Date(evt.createdOn ?? evt.timestamp).getTime();
}

function RecentEventsWidget({ config, refreshToken }: WidgetProps) {
    const navigate = useNavigate();
    const [events, setEvents] = useState<StreamEvent[]>([]);
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 10;
    const eventSourceId = typeof config.eventSourceId === "string" && config.eventSourceId
        ? config.eventSourceId : undefined;

    useEffect(() => {
        let cancelled = false;
        fetchStreamEvents(1, maxRows, undefined, eventSourceId)
            .then(result => {
                if (cancelled) return;
                // The API orders by source timestamp; show the page newest-received first.
                setEvents([...result.items].sort((a, b) => receivedTime(b) - receivedTime(a)));
                setError(false);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxRows, eventSourceId, refreshToken]);

    if (error) return <WidgetError />;

    const formatTime = (evt: StreamEvent) => new Date(evt.createdOn ?? evt.timestamp).toLocaleString(undefined, {
        month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
    });

    return (
        <Table aria-label="Recent Events" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Source</Th><Th>Type</Th><Th>Ref</Th><Th>Processing</Th><Th>Received</Th>
            </Tr></Thead>
            <Tbody>
                {events.map(evt => {
                    const indicator = processingIndicator(evt.processingStates);
                    return (
                        <Tr key={evt.id} isClickable onRowClick={() => navigate(`/events/stream/${evt.id}`)}>
                            <Td><Label isCompact color={sourceColor(evt.source)}>{evt.source}</Label></Td>
                            <Td>{evt.type}</Td>
                            <Td title={evt.ref}>{shortRef(evt.ref)}</Td>
                            <Td><Label isCompact color={indicator.color}>{indicator.text}</Label></Td>
                            <Td>{formatTime(evt)}</Td>
                        </Tr>
                    );
                })}
                {events.length === 0 && (
                    <Tr><Td colSpan={5}>No recent events.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "recent-events",
    name: "Recent Events",
    description: "Latest stream events from all event sources (or one) with their processing state. Click a row "
        + "to open the event.",
    category: "Events",
    scope: "global",
    liveEvents: [SSE_EVENTS.streamEvent, SSE_EVENTS.streamProcessing],
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10, min: 1, max: 100 },
        { key: "eventSourceId", label: "Event source", type: "eventSource", default: null,
          helperText: "Leave empty to show events from all sources." },
    ],
    component: RecentEventsWidget,
});
