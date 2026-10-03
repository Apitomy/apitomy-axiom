import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
    Alert,
    ExpandableSection,
    Label,
    Spinner,
    ToggleGroup,
    ToggleGroupItem,
} from "@patternfly/react-core";
import {
    fetchLineage,
    type LineageEntityType,
    type LineageGraph,
    type LineageNode,
} from "../config/api";
import { statusColor } from "./TraceGraphNode";

type View = "upstream" | "downstream";

const TYPE_LABELS: Record<string, string> = {
    "event": "Event",
    "trace": "Trace",
    "workflow-run": "Workflow run",
    "task": "Task",
    "scheduled-job-run": "Job run",
    "report": "Report",
    "outcome": "Outcome",
};

/** Relation wording when reading an edge from the downstream node towards its origin. */
const UPSTREAM_WORDING: Record<string, string> = {
    "triggered": "triggered by",
    "created": "created by",
    "resumed": "resumed by",
    "produced": "produced by",
    "part-of": "part of",
    "triggered-by-agent": "triggered by an agent in",
};

interface LineagePanelProps {
    entityType: LineageEntityType;
    id: string | number;
    /** When false the panel is shown open, without the expandable header (e.g. inside a tab or modal). */
    expandable?: boolean;
}

function formatCost(cost: number): string {
    return `$${cost.toFixed(cost >= 1 ? 2 : 4)}`;
}

function NodeLine({ node, relation }: { node: LineageNode; relation?: string }) {
    const label = node.linkPath && node.available
        ? <Link to={node.linkPath}>{node.label}</Link>
        : <span style={{ fontStyle: node.available ? undefined : "italic" }}>{node.label}</span>;
    return (
        <span data-testid={`lineage-node-${node.key}`}>
            {relation && (
                <span style={{ color: "var(--pf-t--global--text--color--subtle)", marginRight: "6px" }}>
                    {relation}
                </span>
            )}
            <Label isCompact variant="outline" style={{ marginRight: "6px" }}>
                {TYPE_LABELS[node.type] ?? node.type}
            </Label>
            {label}
            {node.status && (
                <Label isCompact color={node.available ? statusColor(node.status) : "grey"}
                    style={{ marginLeft: "6px" }}>
                    {node.status}
                </Label>
            )}
            {node.costUsd != null && node.costUsd > 0 && (
                <span style={{ marginLeft: "6px" }} title="Direct AI cost">{formatCost(node.costUsd)}</span>
            )}
        </span>
    );
}

/**
 * Renders the graph as an indented tree starting at the root. The origin view follows edges
 * backwards (towards what caused the root); the results view follows them forwards. A node that
 * is reachable twice is expanded only the first time.
 */
function LineageTree({ graph, view }: { graph: LineageGraph; view: View }) {
    const nodes = new Map(graph.nodes.map((n) => [n.key, n]));
    const seen = new Set<string>();

    const render = (key: string, relation: string | undefined, level: number): ReactNode => {
        const node = nodes.get(key);
        if (!node) return null;
        const repeated = seen.has(key);
        seen.add(key);
        const next = repeated ? [] : graph.edges
            .filter((e) => (view === "upstream" ? e.to === key : e.from === key))
            .map((e) => ({
                key: view === "upstream" ? e.from : e.to,
                relation: view === "upstream"
                    ? (UPSTREAM_WORDING[e.relation] ?? e.relation)
                    : e.relation,
            }))
            .filter((c) => nodes.get(c.key)?.direction !== "root");
        return (
            <li key={`${level}-${key}-${relation ?? ""}`} style={{ margin: "4px 0" }}>
                <NodeLine node={node} relation={relation} />
                {repeated && <span style={{ marginLeft: "6px" }}>(shown above)</span>}
                {next.length > 0 && (
                    <ul style={{ listStyle: "none", paddingLeft: "24px",
                        borderLeft: "1px solid var(--pf-t--global--border--color--default)" }}>
                        {next.map((c) => render(c.key, c.relation, level + 1))}
                    </ul>
                )}
            </li>
        );
    };

    const hasOthers = graph.nodes.some((n) => n.direction !== "root");
    return (
        <>
            <ul style={{ listStyle: "none", paddingLeft: 0 }}>{render(graph.root, undefined, 0)}</ul>
            {!hasOthers && (
                <p data-testid="lineage-empty">
                    {view === "upstream" ? "No recorded origin." : "No recorded results."}
                </p>
            )}
        </>
    );
}

/**
 * Lineage panel (#430): shows where an entity came from ("Show origin") and what it produced
 * ("Show results"), with links to each item. Data is loaded only when the panel is opened, and each
 * view is fetched once.
 */
export function LineagePanel({ entityType, id, expandable = true }: LineagePanelProps) {
    const [expanded, setExpanded] = useState(!expandable);
    const [view, setView] = useState<View>("upstream");
    const [graphs, setGraphs] = useState<Partial<Record<View, LineageGraph>>>({});
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        setGraphs({});
        setError(null);
    }, [entityType, id]);

    const graph = graphs[view];
    useEffect(() => {
        if (!expanded || graph) return;
        let cancelled = false;
        setError(null);
        fetchLineage(entityType, id, view)
            .then((g) => { if (!cancelled) setGraphs((prev) => ({ ...prev, [view]: g })); })
            .catch((e: Error) => { if (!cancelled) setError(e.message); });
        return () => { cancelled = true; };
    }, [expanded, view, graph, entityType, id]);

    const body = (
        <div data-testid="lineage-panel">
            <ToggleGroup aria-label="Lineage view" style={{ marginBottom: "12px" }}>
                <ToggleGroupItem text="Show origin" buttonId="lineage-origin"
                    isSelected={view === "upstream"} onChange={() => setView("upstream")} />
                <ToggleGroupItem text="Show results" buttonId="lineage-results"
                    isSelected={view === "downstream"} onChange={() => setView("downstream")} />
            </ToggleGroup>
            {error && <Alert variant="danger" isInline isPlain title={`Could not load lineage: ${error}`} />}
            {!error && !graph && <Spinner size="md" aria-label="Loading lineage" />}
            {graph && (
                <>
                    {graph.truncated && (
                        <Alert variant="info" isInline isPlain style={{ marginBottom: "8px" }}
                            title={`Showing part of the lineage (limits: ${graph.depth} levels, `
                                + `${graph.maxNodes} items).`} />
                    )}
                    <LineageTree graph={graph} view={view} />
                    {graph.totalCostUsd != null && graph.totalCostUsd > 0 && (
                        <div style={{ marginTop: "8px" }}>
                            Total AI cost shown: <strong>{formatCost(graph.totalCostUsd)}</strong>
                        </div>
                    )}
                </>
            )}
        </div>
    );

    if (!expandable) return body;
    return (
        <ExpandableSection toggleText="Lineage" isExpanded={expanded}
            onToggle={(_e, v) => setExpanded(v)} style={{ marginTop: "24px" }}>
            {body}
        </ExpandableSection>
    );
}
