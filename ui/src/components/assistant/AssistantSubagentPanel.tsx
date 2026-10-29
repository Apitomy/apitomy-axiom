import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Tab, TabTitleText, Tabs } from "@patternfly/react-core";
import {
    AssistantSubagentCard,
    type SubagentCardData,
} from "./AssistantSubagentCard";
import {
    AssistantBackgroundTaskCard,
    type BackgroundTaskCardData,
} from "./AssistantBackgroundTaskCard";
import {
    AssistantTodoPanel,
    completedTodoCount,
    type AssistantTodo,
} from "./AssistantTodoPanel";
import "./AssistantSubagentPanel.css";

interface AssistantSubagentPanelProps {
    subagentCards: SubagentCardData[];
    backgroundTaskCards: BackgroundTaskCardData[];
    todos: AssistantTodo[];
    onDismissSubagent: (id: string) => void;
    onDismissBackgroundTask: (id: string) => void;
    onDismissAllCompleted: () => void;
    onNavigateToAgent?: (toolUseId: string) => void;
    onPermissionRespond?: (permissionId: string, allow: boolean, toolInput?: Record<string, unknown>) => void;
    onAllowAll?: (subagentToolUseId: string) => void;
    highlightedCardId?: string;
    /** Width of the side column; ignored in the popover variant. */
    width?: number;
    onWidthChange?: (width: number) => void;
    /** "side" renders a resizable column; "popover" renders the content to fill its container. */
    variant?: "side" | "popover";
    /** When given, the active tab is controlled by the parent. */
    activeTab?: SubagentPanelTab;
    onActiveTabChange?: (tab: SubagentPanelTab) => void;
}

/** The tabs of the subagent panel. */
export type SubagentPanelTab = "subagents" | "background" | "todos";

/**
 * Returns the tab to show first: the first one that has content.
 *
 * @param subagentCount number of subagent cards
 * @param backgroundCount number of background task cards
 * @param todoCount number of todos
 * @returns the initial tab
 */
export function initialSubagentPanelTab(subagentCount: number, backgroundCount: number,
        todoCount: number): SubagentPanelTab {
    if (subagentCount > 0) return "subagents";
    if (backgroundCount > 0) return "background";
    return todoCount > 0 ? "todos" : "subagents";
}

const MIN_WIDTH = 200;
const MAX_WIDTH = 500;

export function AssistantSubagentPanel({
    subagentCards, backgroundTaskCards, todos,
    onDismissSubagent, onDismissBackgroundTask, onDismissAllCompleted,
    onNavigateToAgent, onPermissionRespond, onAllowAll,
    highlightedCardId, width = 390, onWidthChange,
    variant = "side", activeTab: controlledTab, onActiveTabChange,
}: AssistantSubagentPanelProps) {
    // Open on whichever tab has content when the panel first appears.
    const [uncontrolledTab, setUncontrolledTab] = useState<SubagentPanelTab>(
        () => initialSubagentPanelTab(subagentCards.length, backgroundTaskCards.length, todos.length));
    const activeTab: SubagentPanelTab = controlledTab ?? uncontrolledTab;
    const setActiveTab = (tab: SubagentPanelTab): void => {
        setUncontrolledTab(tab);
        onActiveTabChange?.(tab);
    };
    const draggingRef = useRef(false);
    const startXRef = useRef(0);
    const startWidthRef = useRef(0);

    const hasCompletedSubagents = subagentCards.some((c) => c.status === "completed");
    const hasCompletedBgTasks = backgroundTaskCards.some((c) => c.status === "completed");

    const onMouseMove = useCallback((e: MouseEvent) => {
        if (!draggingRef.current) return;
        const delta = startXRef.current - e.clientX;
        const newWidth = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, startWidthRef.current + delta));
        onWidthChange?.(newWidth);
    }, [onWidthChange]);

    const onMouseUp = useCallback(() => {
        draggingRef.current = false;
        document.body.style.cursor = "";
        document.body.style.userSelect = "";
    }, []);

    useEffect(() => {
        document.addEventListener("mousemove", onMouseMove);
        document.addEventListener("mouseup", onMouseUp);
        return () => {
            document.removeEventListener("mousemove", onMouseMove);
            document.removeEventListener("mouseup", onMouseUp);
        };
    }, [onMouseMove, onMouseUp]);

    const handleMouseDown = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        draggingRef.current = true;
        startXRef.current = e.clientX;
        startWidthRef.current = width;
        document.body.style.cursor = "col-resize";
        document.body.style.userSelect = "none";
    }, [width]);

    const isPopover: boolean = variant === "popover";

    return (
        <div className={isPopover ? "axiom-subagent-panel axiom-subagent-panel--popover" : "axiom-subagent-panel"}
            style={isPopover ? undefined : { width }}>
            {!isPopover && (
                <div className="axiom-subagent-panel__resize-handle" onMouseDown={handleMouseDown} />
            )}
            <Tabs
                activeKey={activeTab}
                onSelect={(_e, key) => setActiveTab(key as SubagentPanelTab)}
                className="axiom-subagent-panel__tabs"
            >
                <Tab
                    eventKey="subagents"
                    title={<TabTitleText>Subagents ({subagentCards.length})</TabTitleText>}
                />
                <Tab
                    eventKey="background"
                    title={<TabTitleText>Background ({backgroundTaskCards.length})</TabTitleText>}
                />
                <Tab
                    eventKey="todos"
                    title={<TabTitleText>Todos ({completedTodoCount(todos)}/{todos.length})</TabTitleText>}
                />
            </Tabs>
            <div className="axiom-subagent-panel__tab-content">
                {activeTab === "subagents" && (
                    <>
                        {hasCompletedSubagents && (
                            <div className="axiom-subagent-panel__toolbar">
                                <Button variant="link" size="sm" onClick={onDismissAllCompleted}>
                                    Close All
                                </Button>
                            </div>
                        )}
                        <div className="axiom-subagent-panel__cards">
                            {subagentCards.map((card) => (
                                <AssistantSubagentCard
                                    key={card.id}
                                    card={card}
                                    onDismiss={onDismissSubagent}
                                    onNavigateToAgent={onNavigateToAgent}
                                    onPermissionRespond={onPermissionRespond}
                                    onAllowAll={onAllowAll}
                                    highlighted={highlightedCardId === card.id}
                                />
                            ))}
                        </div>
                    </>
                )}
                {activeTab === "todos" && (
                    todos.length > 0
                        ? <AssistantTodoPanel todos={todos} />
                        : <div className="axiom-subagent-panel__empty">No todos yet.</div>
                )}
                {activeTab === "background" && (
                    <>
                        {hasCompletedBgTasks && (
                            <div className="axiom-subagent-panel__toolbar">
                                <Button variant="link" size="sm" onClick={onDismissAllCompleted}>
                                    Close All
                                </Button>
                            </div>
                        )}
                        <div className="axiom-subagent-panel__cards">
                            {backgroundTaskCards.map((card) => (
                                <AssistantBackgroundTaskCard
                                    key={card.id}
                                    card={card}
                                    onDismiss={onDismissBackgroundTask}
                                />
                            ))}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
