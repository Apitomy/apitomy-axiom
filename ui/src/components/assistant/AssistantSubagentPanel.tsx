import { useCallback, useEffect, useRef } from "react";
import { Button } from "@patternfly/react-core";
import {
    AssistantSubagentCard,
    type SubagentCardData,
} from "./AssistantSubagentCard";
import "./AssistantSubagentPanel.css";

interface AssistantSubagentPanelProps {
    cards: SubagentCardData[];
    onDismiss: (id: string) => void;
    onDismissAllCompleted: () => void;
    onNavigateToAgent?: (toolUseId: string) => void;
    highlightedCardId?: string;
    width: number;
    onWidthChange: (width: number) => void;
}

const MIN_WIDTH = 200;
const MAX_WIDTH = 500;

export function AssistantSubagentPanel({
    cards, onDismiss, onDismissAllCompleted, onNavigateToAgent, highlightedCardId,
    width, onWidthChange,
}: AssistantSubagentPanelProps) {
    const hasCompleted = cards.some((c) => c.status === "completed");
    const draggingRef = useRef(false);
    const startXRef = useRef(0);
    const startWidthRef = useRef(0);

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
            <div className="axiom-subagent-panel__header">
                <span className="axiom-subagent-panel__title">Subagents</span>
                {hasCompleted && (
                    <Button variant="link" size="sm" onClick={onDismissAllCompleted}>
                        Close All
                    </Button>
                )}
            </div>
            <div className="axiom-subagent-panel__cards">
                {cards.map((card) => (
                    <AssistantSubagentCard
                        key={card.id}
                        card={card}
                        onDismiss={onDismiss}
                        onNavigateToAgent={onNavigateToAgent}
                        highlighted={highlightedCardId === card.id}
                    />
                ))}
            </div>
        </div>
    );
}
