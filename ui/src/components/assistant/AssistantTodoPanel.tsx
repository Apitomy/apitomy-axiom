import { useState } from "react";
import { ExpandableSection } from "@patternfly/react-core";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import InProgressIcon from "@patternfly/react-icons/dist/esm/icons/in-progress-icon";
import OutlinedCircleIcon from "@patternfly/react-icons/dist/esm/icons/outlined-circle-icon";
import TimesCircleIcon from "@patternfly/react-icons/dist/esm/icons/times-circle-icon";
import "./AssistantTodoPanel.css";

/**
 * A single item in the assistant's todo list, as carried by the `todos` SSE event.
 */
export interface AssistantTodo {
    content: string;
    status: string;
    priority?: string;
    activeForm?: string;
}

function statusIcon(status: string) {
    switch (status) {
        case "completed":
            return <CheckCircleIcon />;
        case "in_progress":
            return <InProgressIcon />;
        case "cancelled":
            return <TimesCircleIcon />;
        default:
            return <OutlinedCircleIcon />;
    }
}

/**
 * Collapsible panel showing the assistant's current todo list. Renders nothing when the list is empty.
 */
export function AssistantTodoPanel({ todos }: { todos: AssistantTodo[] }) {
    const [expanded, setExpanded] = useState(true);
    if (todos.length === 0) {
        return null;
    }
    const done: number = todos.filter(t => t.status === "completed").length;
    return (
        <div className="axiom-todo-panel">
            <ExpandableSection
                toggleText={`Todos (${done}/${todos.length} done)`}
                isExpanded={expanded}
                onToggle={(_e, value) => setExpanded(value)}
            >
                <ul className="axiom-todo-panel__list">
                    {todos.map((todo, idx) => {
                        const label: string = todo.status === "in_progress" && todo.activeForm
                            ? todo.activeForm : todo.content;
                        return (
                            <li key={idx}
                                className={`axiom-todo-panel__item axiom-todo-panel__item--${todo.status}`}>
                                <span className="axiom-todo-panel__icon">{statusIcon(todo.status)}</span>
                                <span className="axiom-todo-panel__text">{label}</span>
                            </li>
                        );
                    })}
                </ul>
            </ExpandableSection>
        </div>
    );
}
