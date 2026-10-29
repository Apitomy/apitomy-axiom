import { completedTodoCount, type AssistantTodo } from "./AssistantTodoPanel";
import type { SubagentCardData } from "./AssistantSubagentCard";
import type { BackgroundTaskCardData } from "./AssistantBackgroundTaskCard";
import "./AssistantActivityStrip.css";

function plural(count: number, word: string): string {
    return `${count} ${word}${count === 1 ? "" : "s"}`;
}

/**
 * Builds the one-line summary of the assistant's subagents, background tasks and todos.
 * Only the parts that have content are included.
 *
 * @param subagentCards the visible subagent cards
 * @param backgroundTaskCards the visible background task cards
 * @param todos the current todos
 * @returns the summary, e.g. "Todos 3/5 · 1 subagent running · 1 background task running";
 *     empty when there is nothing to show
 */
export function activityStripSummary(subagentCards: SubagentCardData[],
        backgroundTaskCards: BackgroundTaskCardData[], todos: AssistantTodo[]): string {
    const parts: string[] = [];
    if (todos.length > 0) {
        parts.push(`Todos ${completedTodoCount(todos)}/${todos.length}`);
    }
    if (subagentCards.length > 0) {
        const running: number = subagentCards.filter((c) => c.status === "running").length;
        parts.push(running > 0
            ? `${plural(running, "subagent")} running`
            : `${plural(subagentCards.length, "subagent")} done`);
    }
    if (backgroundTaskCards.length > 0) {
        const running: number = backgroundTaskCards.filter((c) => c.status === "running").length;
        parts.push(running > 0
            ? `${plural(running, "background task")} running`
            : `${plural(backgroundTaskCards.length, "background task")} done`);
    }
    const approvals: number = subagentCards
        .reduce((n, c) => n + c.permissions.filter((p) => !p.resolved).length, 0);
    if (approvals > 0) {
        parts.push(`${plural(approvals, "approval")} needed`);
    }
    return parts.join(" · ");
}

interface AssistantActivityStripProps {
    /** The summary text; the strip is not rendered when empty. */
    summary: string;
    /** Highlights the strip, e.g. while a subagent approval is pending. */
    attention: boolean;
    /** Whether the popover is open. */
    expanded: boolean;
    onToggle: () => void;
}

/**
 * A compact single-line strip summarizing subagents, background tasks and todos.
 * Clicking it toggles the details popover.
 */
export function AssistantActivityStrip({ summary, attention, expanded, onToggle }: AssistantActivityStripProps) {
    if (summary === "") {
        return null;
    }
    const className: string = attention
        ? "axiom-activity-strip axiom-activity-strip--attention"
        : "axiom-activity-strip";
    return (
        <button type="button" className={className} onClick={onToggle} aria-expanded={expanded}
            data-testid="assistant-activity-strip">
            <span className="axiom-activity-strip__summary">{summary}</span>
            <span className="axiom-activity-strip__chevron" aria-hidden="true">{expanded ? "▾" : "▴"}</span>
        </button>
    );
}
