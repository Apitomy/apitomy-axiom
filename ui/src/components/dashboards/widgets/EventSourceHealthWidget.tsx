import { useState, useEffect } from "react";
import { Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type EventSource, fetchEventSources } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

function EventSourceHealthWidget({ labels }: WidgetProps) {
    const [sources, setSources] = useState<EventSource[]>([]);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        fetchEventSources()
            .then(all => {
                if (cancelled) return;
                if (labels.length > 0) {
                    setSources(all.filter(s =>
                        s.labels?.some((l: string) => labels.includes(l))
                    ));
                } else {
                    setSources(all);
                }
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels]);

    if (error) return <WidgetError />;

    return (
        <Table aria-label="Event Source Health" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Name</Th><Th>Type</Th><Th>Status</Th>
            </Tr></Thead>
            <Tbody>
                {sources.map(s => (
                    <Tr key={s.id}>
                        <Td>{s.name}</Td>
                        <Td><Label isCompact>{s.sourceType}</Label></Td>
                        <Td>
                            <Label isCompact color={s.enabled ? "green" : "grey"}>
                                {s.enabled ? "Enabled" : "Disabled"}
                            </Label>
                        </Td>
                    </Tr>
                ))}
                {sources.length === 0 && (
                    <Tr><Td colSpan={3}>No event sources configured.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "event-source-health",
    name: "Event Source Health",
    description: "List of event sources with enabled/disabled status.",
    category: "System",
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 2 },
    configSchema: [],
    component: EventSourceHealthWidget,
});
