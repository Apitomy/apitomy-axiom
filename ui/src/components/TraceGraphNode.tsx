import { Handle, Position } from "@xyflow/react";
import { Label, type LabelProps } from "@patternfly/react-core";
import BoltIcon from "@patternfly/react-icons/dist/esm/icons/bolt-icon";
import CogIcon from "@patternfly/react-icons/dist/esm/icons/cog-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import PlayIcon from "@patternfly/react-icons/dist/esm/icons/play-icon";
import WrenchIcon from "@patternfly/react-icons/dist/esm/icons/wrench-icon";
import FileAltIcon from "@patternfly/react-icons/dist/esm/icons/file-alt-icon";
import TimesCircleIcon from "@patternfly/react-icons/dist/esm/icons/times-circle-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import MinusCircleIcon from "@patternfly/react-icons/dist/esm/icons/minus-circle-icon";
import FolderIcon from "@patternfly/react-icons/dist/esm/icons/folder-icon";
import type { TraceNode } from "../config/api";
import "./TraceGraphNode.css";

const NODE_TYPE_ICONS: Record<string, React.ComponentType> = {
    "event-ingested": BoltIcon,
    "manager-evaluation": CogIcon,
    "decision-processed": CheckCircleIcon,
    "project-created": FolderIcon,
    "task": PlayIcon,
    "task-completed": CheckCircleIcon,
    "task-failed": TimesCircleIcon,
    "tool-execution": WrenchIcon,
    "report-triggered": FileAltIcon,
    "report-ai-invoked": CogIcon,
    "report-completed": CheckCircleIcon,
    "report-failed": TimesCircleIcon,
    "event-ignored": MinusCircleIcon,
    "escalation": ExclamationTriangleIcon,
};

export const STATUS_COLORS: Record<string, LabelProps["color"]> = {
    "in-progress": "blue",
    "completed": "green",
    "failed": "red",
    "skipped": "grey",
};

export function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

function NodeTypeIcon({ nodeType }: { nodeType: string }) {
    const Icon = NODE_TYPE_ICONS[nodeType] || CogIcon;
    return <Icon />;
}

export function TraceGraphNode({ data }: { data: TraceNode }) {
    return (
        <div className="axiom-trace-graph-node" data-status={data.status} data-node-type={data.nodeType}>
            <Handle type="target" position={Position.Left} />

            <div className="axiom-trace-graph-node__header">
                <NodeTypeIcon nodeType={data.nodeType} />
                <Label isCompact color={STATUS_COLORS[data.status]}>
                    {data.status}
                </Label>
            </div>
            <div className="axiom-trace-graph-node__summary">
                {data.summary}
            </div>
            <div className="axiom-trace-graph-node__timing">
                {data.durationMs != null && formatDuration(data.durationMs)}
                {data.durationMs != null && " · "}
                {new Date(data.startedOn).toLocaleTimeString()}
            </div>

            <Handle type="source" position={Position.Right} />
        </div>
    );
}

export const nodeTypes = { traceNode: TraceGraphNode };
