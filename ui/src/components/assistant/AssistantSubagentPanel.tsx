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
}

export function AssistantSubagentPanel({
    cards, onDismiss, onDismissAllCompleted, onNavigateToAgent, highlightedCardId,
}: AssistantSubagentPanelProps) {
    const hasCompleted = cards.some((c) => c.status === "completed");

    return (
        <div className="axiom-subagent-panel">
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
