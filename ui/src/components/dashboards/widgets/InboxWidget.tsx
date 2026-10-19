import { useState, useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import {
    type InboxItem, type WaitingWorkflowRun, fetchInboxItems, fetchWaitingWorkflowRuns,
} from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { SSE_EVENTS } from "../../../config/sseEvents";
import { formatAge } from "./widgetUtils";

function InboxWidget({ config, labels, refreshToken }: WidgetProps) {
    const navigate = useNavigate();
    const [items, setItems] = useState<InboxItem[]>([]);
    const [runsByTask, setRunsByTask] = useState<Map<number, WaitingWorkflowRun>>(new Map());
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 10;

    useEffect(() => {
        let cancelled = false;
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;
        fetchInboxItems(1, maxRows, labelsParam)
            .then(result => { if (!cancelled) { setItems(result.items); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        // Inbox items do not carry their workflow run; waiting human-task runs map task id -> run.
        fetchWaitingWorkflowRuns(labels, 200)
            .then(runs => {
                if (cancelled) return;
                const map = new Map<number, WaitingWorkflowRun>();
                runs.filter(r => r.taskId != null).forEach(r => map.set(r.taskId as number, r));
                setRunsByTask(map);
            })
            .catch(() => { if (!cancelled) setRunsByTask(new Map()); });
        return () => { cancelled = true; };
    }, [labels, maxRows, refreshToken]);

    if (error) return <WidgetError />;

    return (
        <Table aria-label="Tasks Awaiting Input" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Task</Th><Th>Subject</Th><Th>Workflow run</Th><Th>Age</Th>
            </Tr></Thead>
            <Tbody>
                {items.map(item => {
                    const run = runsByTask.get(item.id);
                    return (
                        <Tr key={item.id} isClickable
                            onRowClick={() => navigate(`/inbox?task=${item.id}`)}>
                            <Td>{item.humanContext?.title || item.actionType}</Td>
                            <Td>{item.subjectName || "-"}</Td>
                            <Td>
                                {run ? (
                                    <Link to={`/runs/${run.runId}`} onClick={e => e.stopPropagation()}>
                                        {run.runTitle || run.workflowName}
                                    </Link>
                                ) : "-"}
                            </Td>
                            <Td>{formatAge(item.createdOn)}</Td>
                        </Tr>
                    );
                })}
                {items.length === 0 && (
                    <Tr><Td colSpan={4}>No tasks are waiting for input</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "inbox",
    name: "Tasks Awaiting Input",
    description: "Human tasks waiting for input, with their subject, workflow run and age. Click a row to open "
        + "the task in the Inbox.",
    category: "Inbox",
    scope: "subject-labels",
    liveEvents: [SSE_EVENTS.inboxUpdated],
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 10 },
    ],
    component: InboxWidget,
});
