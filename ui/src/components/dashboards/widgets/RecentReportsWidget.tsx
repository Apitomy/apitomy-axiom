import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type Report, fetchReports } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    pending: "blue", running: "orange", completed: "green", failed: "red",
};

function RecentReportsWidget({ config, labels }: WidgetProps) {
    const navigate = useNavigate();
    const [reports, setReports] = useState<Report[]>([]);
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 10;

    useEffect(() => {
        let cancelled = false;
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;
        fetchReports(1, maxRows, undefined, undefined, undefined, labelsParam)
            .then(result => { if (!cancelled) setReports(result.items); })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, maxRows]);

    if (error) return <WidgetError />;

    const formatCost = (cost?: number) => cost != null ? `$${cost.toFixed(2)}` : "—";

    return (
        <Table aria-label="Recent Reports" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Title</Th><Th>Status</Th><Th>Cost</Th><Th>Date</Th>
            </Tr></Thead>
            <Tbody>
                {reports.map(r => (
                    <Tr key={r.id} isClickable
                        onRowClick={() => navigate(`/reports/${r.id}`)}>
                        <Td>{r.title || `Report #${r.id}`}</Td>
                        <Td>
                            <Label isCompact color={STATUS_COLORS[r.status] || "grey"}>
                                {r.status}
                            </Label>
                        </Td>
                        <Td>{formatCost(r.costUsd)}</Td>
                        <Td>{new Date(r.createdOn).toLocaleDateString()}</Td>
                    </Tr>
                ))}
                {reports.length === 0 && (
                    <Tr><Td colSpan={4}>No recent reports.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "recent-reports",
    name: "Recent Reports",
    description: "Table of recently generated reports with title, status, and cost.",
    category: "Reports",
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10 },
    ],
    component: RecentReportsWidget,
});
