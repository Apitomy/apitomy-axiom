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
import "./AssistantSubagentPanel.css";

interface AssistantSubagentPanelProps {
    subagentCards: SubagentCardData[];
    backgroundTaskCards: BackgroundTaskCardData[];
    onDismissSubagent: (id: string) => void;
    onDismissBackgroundTask: (id: string) => void;
    onDismissAllCompleted: () => void;
    onNavigateToAgent?: (toolUseId: string) => void;
    onPermissionRespond?: (permissionId: string, allow: boolean, toolInput?: Record<string, unknown>) => void;
    highlightedCardId?: string;
    width: number;
    onWidthChange: (width: number) => void;
}

const MIN_WIDTH = 200;
const MAX_WIDTH = 500;

export function AssistantSubagentPanel({
    subagentCards, backgroundTaskCards,
    onDismissSubagent, onDismissBackgroundTask, onDismissAllCompleted,
    onNavigateToAgent, onPermissionRespond,
    highlightedCardId, width, onWidthChange,
}: AssistantSubagentPanelProps) {
    const [activeTab, setActiveTab] = useState<string | number>("subagents");
    const draggingRef = useRef(false);
    const startXRef = useRef(0);
    const startWidthRef = useRef(0);

    const hasCompletedSubagents = subagentCards.some((c) => c.status === "completed");
    const hasCompletedBgTasks = backgroundTaskCards.some((c) => c.status === "completed");

    const onMouseMove = useCallback((e: MouseEvent) => {
        if (!draggingRef.current) return;
        const delta = startXRef.current - e.clientX;
        const newWidth = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, startWidthRef.current + delta));
        onWidthChange(newWidth);
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

    return (
        <div className="axiom-subagent-panel" style={{ width }}>
            <div className="axiom-subagent-panel__resize-handle" onMouseDown={handleMouseDown} />
            <Tabs
                activeKey={activeTab}
                onSelect={(_e, key) => setActiveTab(key)}
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
                                    highlighted={highlightedCardId === card.id}
                                />
                            ))}
                        </div>
                    </>
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
