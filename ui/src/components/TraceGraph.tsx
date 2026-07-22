import { useCallback, useEffect, useState } from "react";
import {
    ReactFlow,
    ReactFlowProvider,
    Background,
    Controls,
    Position,
    useNodesState,
    useEdgesState,
    type Node,
    type Edge,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import ELK from "elkjs/lib/elk.bundled.js";
import { EmptyState, EmptyStateBody } from "@patternfly/react-core";
import { useEffectiveTheme } from "../hooks/useTheme";
import { fetchTraceDetail, type TraceDetail, type TraceNode } from "../config/api";
import { nodeTypes } from "./TraceGraphNode";
import { TraceNodeDetailModal } from "./TraceNodeDetailModal";

interface TraceGraphProps {
    traceId: string;
    traceDetail?: TraceDetail;
    refreshKey?: number;
}

const elk = new ELK();

const ELK_OPTIONS: Record<string, string> = {
    "elk.algorithm": "layered",
    "elk.direction": "RIGHT",
    "elk.layered.spacing.nodeNodeBetweenLayers": "120",
    "elk.spacing.nodeNode": "40",
};

async function layoutTrace(traceNodes: TraceNode[]): Promise<{
    nodes: Node[];
    edges: Edge[];
}> {
    if (traceNodes.length === 0) {
        return { nodes: [], edges: [] };
    }

    const graph = {
        id: "root",
        layoutOptions: ELK_OPTIONS,
        children: traceNodes.map((tn) => ({
            id: String(tn.id),
            width: 280,
            height: 72,
        })),
        edges: traceNodes
            .filter((tn) => tn.parentNodeId != null)
            .map((tn) => ({
                id: `e-${tn.parentNodeId}-${tn.id}`,
                sources: [String(tn.parentNodeId)],
                targets: [String(tn.id)],
            })),
    };

    const layouted = await elk.layout(graph);

    const nodeMap = new Map(traceNodes.map((tn) => [String(tn.id), tn]));

    const nodes: Node[] = (layouted.children || []).map((child) => ({
        id: child.id,
        type: "traceNode",
        position: { x: child.x!, y: child.y! },
        data: nodeMap.get(child.id)! as unknown as Record<string, unknown>,
        targetPosition: Position.Left,
        sourcePosition: Position.Right,
    }));

    const edges: Edge[] = (layouted.edges || []).map((edge) => {
        const targetId = edge.targets[0];
        const targetNode = nodeMap.get(targetId);
        return {
            id: edge.id,
            source: edge.sources[0],
            target: targetId,
            type: "smoothstep",
            style: {
                stroke: targetNode?.status === "failed"
                    ? "var(--pf-t--global--color--status--danger--default, #c9190b)"
                    : "var(--pf-t--global--color--status--disabled--default, #8a8d90)",
                strokeWidth: 2,
            },
            animated: targetNode?.status === "in-progress",
        };
    });

    return { nodes, edges };
}

function TraceGraphInner({ traceId, traceDetail, refreshKey }: TraceGraphProps) {
    const effectiveTheme = useEffectiveTheme();
    const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
    const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
    const [loading, setLoading] = useState(true);
    const [selectedNode, setSelectedNode] = useState<TraceNode | null>(null);
    const [modalOpen, setModalOpen] = useState(false);

    const loadTrace = useCallback(async () => {
        setLoading(true);
        try {
            const detail = traceDetail ?? await fetchTraceDetail(traceId);
            const layouted = await layoutTrace(detail.nodes);
            setNodes(layouted.nodes);
            setEdges(layouted.edges);
        } catch (e) {
            console.error("Failed to load trace:", e);
        } finally {
            setLoading(false);
        }
    }, [traceId, traceDetail, refreshKey, setNodes, setEdges]);

    useEffect(() => { loadTrace(); }, [loadTrace]);

    const onNodeClick = useCallback((_event: React.MouseEvent, node: Node) => {
        setSelectedNode(node.data as unknown as TraceNode);
        setModalOpen(true);
    }, []);

    if (loading) {
        return (
            <EmptyState>
                <EmptyStateBody>Loading trace...</EmptyStateBody>
            </EmptyState>
        );
    }

    if (nodes.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>No trace nodes found.</EmptyStateBody>
            </EmptyState>
        );
    }

    return (
        <div style={{ height: "100%", minHeight: "400px", width: "100%" }}>
            <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onNodeClick={onNodeClick}
                nodeTypes={nodeTypes}
                fitView
                nodesConnectable={false}
                nodesDraggable={false}
                colorMode={effectiveTheme}
            >
                <Background />
                <Controls />
            </ReactFlow>

            <TraceNodeDetailModal
                isOpen={modalOpen}
                traceId={traceId}
                node={selectedNode}
                onClose={() => { setModalOpen(false); setSelectedNode(null); }}
            />
        </div>
    );
}

export function TraceGraph(props: TraceGraphProps) {
    return (
        <ReactFlowProvider>
            <TraceGraphInner {...props} />
        </ReactFlowProvider>
    );
}
