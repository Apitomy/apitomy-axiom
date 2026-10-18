import { useState, useEffect } from "react";
import { Label } from "@patternfly/react-core";
import { Link, useNavigate } from "react-router-dom";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type ActionType, fetchActionTypes } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { executionModeColor } from "../../executionModeColor";
import { WidgetError } from "../WidgetError";

function ActionTypesWidget({ config }: WidgetProps) {
    const navigate = useNavigate();
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [error, setError] = useState(false);
    const maxRows = Math.min(Number(config.maxRows) || 10, 100);

    // Dashboard labels are subject labels, so they are not applied to action types.
    useEffect(() => {
        let cancelled = false;
        fetchActionTypes(1, maxRows)
            .then(results => {
                if (cancelled) return;
                setActionTypes(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxRows]);

    if (error) return <WidgetError />;

    return (
        <Table aria-label="Action Types" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Name</Th><Th>Mode</Th>
            </Tr></Thead>
            <Tbody>
                {actionTypes.map(at => (
                    <Tr key={at.id} isClickable onRowClick={() => navigate(`/action-types/${at.id}`)}>
                        <Td>{at.name}</Td>
                        <Td>
                            <Label isCompact color={executionModeColor(at.executionMode)}>
                                {at.executionMode}
                            </Label>
                        </Td>
                    </Tr>
                ))}
                {actionTypes.length === 0 && (
                    <Tr><Td colSpan={2}>No action types found.</Td></Tr>
                )}
                {totalCount > actionTypes.length && (
                    <Tr><Td colSpan={2}>
                        <Link to="/action-types">Showing {actionTypes.length} of {totalCount}. View all</Link>
                    </Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "action-types",
    name: "Action Types",
    description: "Action types with their execution mode. Click a row to open the action type. Not filtered by dashboard labels.",
    category: "System",
    scope: "global",
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10, min: 1, max: 100 },
    ],
    component: ActionTypesWidget,
});
