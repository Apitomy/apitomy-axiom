import { Button, Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { Link } from "react-router-dom";
import type { HistoryEntryInfo } from "../config/api";

interface WorkflowStepTimelineProps {
    history: HistoryEntryInfo[];
    traceId?: string;
    onViewLog: (taskId: number) => void;
}

const TASK_STATUS_COLORS: Record<string, "blue" | "green" | "grey" | "red" | "orange"> = {
    Pending: "grey",
    InProgress: "blue",
    AwaitingInput: "orange",
    Completed: "green",
    Failed: "red",
    Cancelled: "grey",
};

function stepDuration(entry: HistoryEntryInfo): string {
    if (!entry.completedOn) return "—";
    const ms = new Date(entry.completedOn).getTime()
        - new Date(entry.enteredOn).getTime();
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

export function WorkflowStepTimeline({
    history, traceId, onViewLog,
}: WorkflowStepTimelineProps) {
    if (history.length === 0) {
        return <p>No steps have executed yet.</p>;
    }
    return (
        <Table aria-label="Step Timeline" variant="compact">
            <Thead>
                <Tr>
                    <Th>Step</Th>
                    <Th>Task Status</Th>
                    <Th>Entered</Th>
                    <Th>Completed</Th>
                    <Th>Duration</Th>
                    <Th>Actions</Th>
                </Tr>
            </Thead>
            <Tbody>
                {history.map((entry, idx) => (
                    <Tr key={`${entry.nodeId}-${idx}`}>
                        <Td>{entry.nodeName || entry.nodeId}</Td>
                        <Td>
                            {entry.taskStatus && (
                                <Label isCompact
                                    color={TASK_STATUS_COLORS[entry.taskStatus] || "grey"}>
                                    {entry.taskStatus}
                                </Label>
                            )}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {new Date(entry.enteredOn).toLocaleString()}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {entry.completedOn
                                ? new Date(entry.completedOn).toLocaleString() : "—"}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>{stepDuration(entry)}</Td>
                        <Td>
                            {entry.taskId != null && (
                                <Button variant="link" isInline
                                    onClick={() => onViewLog(entry.taskId!)}>
                                    View Log
                                </Button>
                            )}
                            {entry.taskId != null && traceId && " | "}
                            {traceId && (
                                <Link to={`/logs/traces/${traceId}`}>View Trace</Link>
                            )}
                        </Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}
