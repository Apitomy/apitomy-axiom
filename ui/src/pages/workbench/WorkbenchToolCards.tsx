import { Fragment, useState, type ReactNode } from "react";
import { Button, ExpandableSection, Label } from "@patternfly/react-core";
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
import { ToolCallDetails } from "./ToolCallDetails";

/** Navigation callbacks used by the workbench chat cards. */
export interface WorkbenchToolActions {
    /** Opens the tab of the item with the given type and name (no-op when it no longer exists). */
    openItem(type: "action-type" | "tool" | "workflow" | "subscription", name: string): void;
    /**
     * Activates the workflow tab.
     *
     * @param nodeId first touched node; currently unused, reserved for when Flow exposes node selection
     */
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

function actionFor(target: CardTarget | undefined, actions: WorkbenchToolActions): ReactNode {
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
    return <Button variant="secondary" size="sm" onClick={onClick}>{label}</Button>;
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

/** A tool card as a PatternFly disclosure (large expandable section); the details render only when open. */
function Disclosure({ className, header, children }: { className: string; header: ReactNode; children: ReactNode }) {
    const [expanded, setExpanded] = useState<boolean>(false);
    return (
        <div className={`axiom-workbench__tool-card ${className}`}>
            <ExpandableSection displaySize="lg" isIndented isExpanded={expanded}
                onToggle={(_e, open) => setExpanded(open)}
                toggleContent={<span className="axiom-workbench__tool-card-row">{header}</span>}>
                {expanded && children}
            </ExpandableSection>
        </div>
    );
}

function detailsOf(ctx: ToolUseRenderContext): ReactNode {
    const m = ctx.message;
    return <ToolCallDetails tool={workbenchTool(m.toolName ?? "")} input={m.toolInput} result={m.toolResult}
        isError={m.isError} />;
}

function CardRow({ ctx, card, actions }: { ctx: ToolUseRenderContext; card: ToolCard; actions: WorkbenchToolActions }) {
    const danger: boolean = card.variant === "danger";
    const action: ReactNode = actionFor(card.target, actions);
    return (
        <Disclosure className={danger ? "axiom-workbench__tool-card--danger" : ""}
            header={<>
                <span className="axiom-workbench__tool-card-icon">
                    {iconFor(workbenchTool(ctx.message.toolName ?? ""), card)}
                </span>
                <CardText card={card} />
                {card.status && <Label isCompact color={STATUS_COLOR[card.status]}>{card.status}</Label>}
            </>}>
            {detailsOf(ctx)}
            {action && <div className="axiom-workbench__tool-card-actions">{action}</div>}
        </Disclosure>
    );
}

function ReadOnlyLine({ entries }: { entries: { ctx: ToolUseRenderContext; card: ToolCard }[] }) {
    const single: boolean = entries.length === 1;
    return (
        <Disclosure className="axiom-workbench__tool-card--quiet"
            header={<>
                <span className="axiom-workbench__tool-card-icon"><SearchIcon /></span>
                {single ? <CardText card={entries[0].card} />
                    : <span className="axiom-workbench__tool-card-text">{`Read ${entries.length} things`}</span>}
            </>}>
            {single ? detailsOf(entries[0].ctx) : entries.map((e) => (
                <div key={e.ctx.message.id} className="axiom-workbench__tool-card-entry">
                    <div className="axiom-workbench__tool-card-entry-title"><CardText card={e.card} /></div>
                    {detailsOf(e.ctx)}
                </div>
            ))}
        </Disclosure>
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
    const refs: ToolCallRef[] = group.map(({ message: m }, index: number) => ({
        id: String(index),
        toolName: m.toolName ?? "",
        card: m.permissionId && !m.permissionResolved
            ? null : describeToolCall(m.toolName ?? "", m.toolInput, m.toolResult, m.isError),
    }));
    return groupToolCalls(refs).map((calls: ToolCallRef[]) => {
        const first: ToolCallRef = calls[0];
        const ctx: ToolUseRenderContext = group[Number(first.id)];
        const key: string = ctx.message.id;
        if (first.card === null) {
            return <Fragment key={key}>{ctx.genericBlock}</Fragment>;
        }
        if (first.card.readOnly) {
            return <ReadOnlyLine key={key}
                entries={calls.map((c) => ({ ctx: group[Number(c.id)], card: c.card as ToolCard }))} />;
        }
        return <CardRow key={key} ctx={ctx} card={first.card} actions={actions} />;
    });
}
