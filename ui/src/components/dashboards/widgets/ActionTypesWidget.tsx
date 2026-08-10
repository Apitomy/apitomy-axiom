import { useState, useEffect } from "react";
import { Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type ActionType, fetchActionTypes } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

function ActionTypesWidget({ config, labels }: WidgetProps) {
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 10;

    useEffect(() => {
        let cancelled = false;
        fetchActionTypes()
            .then(all => {
                if (cancelled) return;
                let filtered = all;
                if (labels.length > 0) {
                    // Subset logic: include action types with no labels,
                    // or whose labels are a subset of the dashboard's labels.
                    filtered = all.filter(at =>
                        !at.labels || at.labels.length === 0
                        || at.labels.every(l => labels.includes(l))
                    );
                }
                setActionTypes(filtered.slice(0, maxRows));
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, maxRows]);

    if (error) return <WidgetError />;

    return (
        <Table aria-label="Action Types" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Name</Th><Th>Mode</Th><Th>Triggers</Th>
            </Tr></Thead>
            <Tbody>
                {actionTypes.map(at => (
                    <Tr key={at.id}>
                        <Td>{at.name}</Td>
                        <Td>
                            <Label isCompact color={at.executionMode === "actor" ? "blue" : "purple"}>
                                {at.executionMode}
                            </Label>
                        </Td>
                        <Td>
                            {at.managerTriggerable && <Label isCompact color="green" style={{ marginRight: "4px" }}>manager</Label>}
                            {at.userTriggerable && <Label isCompact color="blue">user</Label>}
                            {!at.managerTriggerable && !at.userTriggerable && "—"}
                        </Td>
                    </Tr>
                ))}
                {actionTypes.length === 0 && (
                    <Tr><Td colSpan={3}>No action types found.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "action-types",
    name: "Action Types",
    description: "List of action types with execution mode and trigger status, filtered by dashboard labels.",
    category: "System",
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10 },
    ],
    component: ActionTypesWidget,
});
