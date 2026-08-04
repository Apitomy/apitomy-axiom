import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type InboxItem, fetchInboxItems } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

function InboxWidget({ config }: WidgetProps) {
    const navigate = useNavigate();
    const [items, setItems] = useState<InboxItem[]>([]);
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 10;

    useEffect(() => {
        let cancelled = false;
        fetchInboxItems(1, maxRows)
            .then(result => { if (!cancelled) setItems(result.items); })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxRows]);

    if (error) return <WidgetError />;

    const formatAge = (dateStr: string) => {
        const diffMs = Date.now() - new Date(dateStr).getTime();
        const diffHr = Math.floor(diffMs / 3600000);
        if (diffHr < 24) return `${diffHr}h`;
        return `${Math.floor(diffHr / 24)}d`;
    };

    return (
        <Table aria-label="Inbox" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Project</Th><Th>Action</Th><Th>Age</Th>
            </Tr></Thead>
            <Tbody>
                {items.map(item => (
                    <Tr key={item.id} isClickable
                        onRowClick={() => navigate(`/inbox`)}>
                        <Td>{item.projectName || "—"}</Td>
                        <Td><Label isCompact>{item.actionType}</Label></Td>
                        <Td>{formatAge(item.createdOn)}</Td>
                    </Tr>
                ))}
                {items.length === 0 && (
                    <Tr><Td colSpan={3}>No pending tasks.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "inbox",
    name: "Inbox",
    description: "Pending human tasks awaiting input with project name and age.",
    category: "Operations",
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10 },
    ],
    component: InboxWidget,
});
