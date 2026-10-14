import { Fragment, useState, type ReactNode } from "react";
import { Button, Label } from "@patternfly/react-core";
import AngleDownIcon from "@patternfly/react-icons/dist/esm/icons/angle-down-icon";
import AngleRightIcon from "@patternfly/react-icons/dist/esm/icons/angle-right-icon";
import CodeIcon from "@patternfly/react-icons/dist/esm/icons/code-icon";
import EditIcon from "@patternfly/react-icons/dist/esm/icons/edit-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import FlaskIcon from "@patternfly/react-icons/dist/esm/icons/flask-icon";
import ProjectDiagramIcon from "@patternfly/react-icons/dist/esm/icons/project-diagram-icon";
import SearchIcon from "@patternfly/react-icons/dist/esm/icons/search-icon";
import StreamIcon from "@patternfly/react-icons/dist/esm/icons/stream-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import type { ToolUseRenderContext } from "../../components/assistant/AssistantMessageList";
import { describeToolCall, groupToolCalls, workbenchTool, type CardTarget, type ToolCallRef, type ToolCard,
    type WorkbenchTool } from "./toolCards";

/** Navigation callbacks used by the workbench chat cards. */
export interface WorkbenchToolActions {
    /** Opens the tab of the item with the given type and name (no-op when it no longer exists). */
    openItem(type: "action-type" | "tool" | "workflow", name: string): void;
    /** Activates the workflow tab and selects the node, if given. */
    openWorkflow(nodeId?: string): void;
    /** Opens the newest test suggestion for the item. */
    openSuggestion(type: "action-type" | "tool", name: string): void;
}

function iconFor(tool: WorkbenchTool | null, card: ToolCard): ReactNode {
    if (card.variant === "danger") return <ExclamationCircleIcon />;
    switch (tool) {
        case "upsert_item": return <EditIcon />;
        case "remove_item": return <TrashIcon />;
        case "propose_workflow_changes": return <ProjectDiagramIcon />;
        case "suggest_test": return <FlaskIcon />;
        case "evaluate_expression": return <CodeIcon />;
        case "list_stream_events":
        case "get_stream_event": return <StreamIcon />;
        default: return <SearchIcon />;
    }
}

const STATUS_COLOR: Record<NonNullable<ToolCard["status"]>, "green" | "orange" | "red"> = {
    "VALID": "green", "VALID with warnings": "orange", "INVALID": "red",
};

function linkFor(target: CardTarget | undefined, actions: WorkbenchToolActions): ReactNode {
    if (!target) return null;
    let label: string;
    let onClick: () => void;
    switch (target.kind) {
        case "item":
            label = "Open";
            onClick = () => actions.openItem(target.itemType, target.name);
            break;
        case "workflow":
            label = "Open workflow";
            onClick = () => actions.openWorkflow(target.nodeId);
            break;
        default:
            label = "Run…";
            onClick = () => actions.openSuggestion(target.itemType, target.name);
    }
    return <Button variant="link" isInline size="sm" onClick={onClick}>{label}</Button>;
}

function CardText({ card }: { card: ToolCard }) {
    return (
        <span className="axiom-workbench__tool-card-text" title={card.text}>
            {card.lead}
            {card.name && <> <code>{card.name}</code></>}
            {card.detail && <> {card.detail}</>}
        </span>
    );
}

function ExpandToggle({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
    return (
        <Button variant="plain" size="sm" className="axiom-workbench__tool-card-toggle"
            aria-label={expanded ? "Hide details" : "Show details"} aria-expanded={expanded} onClick={onToggle}
            icon={expanded ? <AngleDownIcon /> : <AngleRightIcon />} />
    );
}

function CardRow({ ctx, card, actions }: { ctx: ToolUseRenderContext; card: ToolCard; actions: WorkbenchToolActions }) {
    const [expanded, setExpanded] = useState<boolean>(false);
    const danger: boolean = card.variant === "danger";
    return (
        <div className={`axiom-workbench__tool-card${danger ? " axiom-workbench__tool-card--danger" : ""}`}>
            <div className="axiom-workbench__tool-card-row">
                <ExpandToggle expanded={expanded} onToggle={() => setExpanded(!expanded)} />
                <span className="axiom-workbench__tool-card-icon">
                    {iconFor(workbenchTool(ctx.message.toolName ?? ""), card)}
                </span>
                <CardText card={card} />
                {card.status && <Label isCompact color={STATUS_COLOR[card.status]}>{card.status}</Label>}
                {linkFor(card.target, actions)}
            </div>
            {expanded && <div className="axiom-workbench__tool-card-details">{ctx.genericBlock}</div>}
        </div>
    );
}

function ReadOnlyLine({ entries }: { entries: { ctx: ToolUseRenderContext; card: ToolCard }[] }) {
    const [expanded, setExpanded] = useState<boolean>(false);
    const single: boolean = entries.length === 1;
    return (
        <div className="axiom-workbench__tool-card axiom-workbench__tool-card--quiet">
            <div className="axiom-workbench__tool-card-row">
                <ExpandToggle expanded={expanded} onToggle={() => setExpanded(!expanded)} />
                <span className="axiom-workbench__tool-card-icon"><SearchIcon /></span>
                {single ? <CardText card={entries[0].card} />
                    : <span className="axiom-workbench__tool-card-text">{`Read ${entries.length} things`}</span>}
            </div>
            {expanded && (
                <div className="axiom-workbench__tool-card-details">
                    {!single && (
                        <ul className="axiom-workbench__tool-card-list">
                            {entries.map((e) => <li key={e.ctx.message.id}><CardText card={e.card} /></li>)}
                        </ul>
                    )}
                    {entries.map((e) => <Fragment key={e.ctx.message.id}>{e.ctx.genericBlock}</Fragment>)}
                </div>
            )}
        </div>
    );
}

/**
 * Renders a run of workbench tool calls as compact cards: consecutive read-only calls collapse into one quiet
 * line; calls without a card (unknown tool, still running, waiting for permission) keep their generic block.
 *
 * @param group the tool calls of one run, in order
 * @param actions navigation callbacks for the card links
 */
export function renderWorkbenchToolGroup(group: ToolUseRenderContext[], actions: WorkbenchToolActions): ReactNode {
    const byId: Map<string, ToolUseRenderContext> = new Map(group.map((g) => [g.message.id, g]));
    const refs: ToolCallRef[] = group.map(({ message: m }) => ({
        id: m.id,
        toolName: m.toolName ?? "",
        card: m.permissionId && !m.permissionResolved
            ? null : describeToolCall(m.toolName ?? "", m.toolInput, m.toolResult, m.isError),
    }));
    return groupToolCalls(refs).map((calls: ToolCallRef[]) => {
        const first: ToolCallRef = calls[0];
        const ctx: ToolUseRenderContext = byId.get(first.id)!;
        if (first.card === null) {
            return <Fragment key={first.id}>{ctx.genericBlock}</Fragment>;
        }
        if (first.card.readOnly) {
            return <ReadOnlyLine key={first.id}
                entries={calls.map((c) => ({ ctx: byId.get(c.id)!, card: c.card as ToolCard }))} />;
        }
        return <CardRow key={first.id} ctx={ctx} card={first.card} actions={actions} />;
    });
}
