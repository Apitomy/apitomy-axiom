import { useState } from "react";
import {
    Button,
    ExpandableSection,
    Label,
    Spinner,
} from "@patternfly/react-core";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import "./AssistantSubagentCard.css";

export interface SubagentActivityEntry {
    id: string;
    toolName: string;
    description: string;
}

export interface SubagentCardData {
    id: string;
    taskId: string;
    description: string;
    subagentType: string;
    status: "running" | "completed";
    currentActivity?: string;
    lastToolName?: string;
    toolCount: number;
    durationMs: number;
    summary?: string;
    activityLog: SubagentActivityEntry[];
    dismissed: boolean;
}

interface AssistantSubagentCardProps {
    card: SubagentCardData;
    onDismiss: (id: string) => void;
    onNavigateToAgent?: (toolUseId: string) => void;
    highlighted?: boolean;
}

export function AssistantSubagentCard({
    card, onDismiss, onNavigateToAgent, highlighted,
}: AssistantSubagentCardProps) {
    const [isExpanded, setIsExpanded] = useState(false);
    const isComplete = card.status === "completed";

    return (
        <div
            className={`axiom-subagent-card${highlighted ? " axiom-subagent-card--highlighted" : ""}`}
            data-status={card.status}
        >
            <div className="axiom-subagent-card__header">
                <div className="axiom-subagent-card__title-row">
                    <Label isCompact color="orange">{card.subagentType}</Label>
                    <span className="axiom-subagent-card__description">
                        {card.description}
                    </span>
                </div>
                {isComplete && (
                    <Button
                        variant="plain"
                        size="sm"
                        aria-label="Dismiss"
                        className="axiom-subagent-card__close-btn"
                        onClick={() => onDismiss(card.id)}
                    >
                        <TimesIcon />
                    </Button>
                )}
            </div>

            <div className="axiom-subagent-card__status">
                {isComplete ? (
                    <span className="axiom-subagent-card__completed">
                        <CheckCircleIcon />
                        Completed in {formatDuration(card.durationMs)}
                        {" · "}{card.toolCount} tools used
                    </span>
                ) : (
                    <span className="axiom-subagent-card__running">
                        <Spinner size="sm" />
                        <span className="axiom-subagent-card__activity">
                            {card.currentActivity || "Starting..."}
                        </span>
                        <span className="axiom-subagent-card__stats">
                            {formatDuration(card.durationMs)} · {card.toolCount} tools
                        </span>
                    </span>
                )}
            </div>

            {card.activityLog.length > 0 && (
                <ExpandableSection
                    toggleText={isExpanded
                        ? "Hide activity"
                        : `Show activity (${card.activityLog.length})`}
                    isExpanded={isExpanded}
                    onToggle={(_e, expanded) => setIsExpanded(expanded)}
                    isIndented
                    className="axiom-subagent-card__activity-section"
                >
                    <div className="axiom-subagent-card__activity-log">
                        {card.activityLog.map((entry) => (
                            <div key={entry.id} className="axiom-subagent-card__activity-entry">
                                <Label isCompact color="blue"
                                    className="axiom-subagent-card__tool-badge">
                                    {entry.toolName}
                                </Label>
                                <span className="axiom-subagent-card__activity-desc">
                                    {entry.description}
                                </span>
                            </div>
                        ))}
                    </div>
                </ExpandableSection>
            )}

            {onNavigateToAgent && (
                <Button
                    variant="link"
                    size="sm"
                    className="axiom-subagent-card__navigate"
                    onClick={() => onNavigateToAgent(card.id)}
                >
                    Show in conversation
                </Button>
            )}
        </div>
    );
}

function formatDuration(ms: number): string {
    const seconds = Math.round(ms / 1000);
    if (seconds < 60) return `${seconds}s`;
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return secs > 0 ? `${mins}m ${secs}s` : `${mins}m`;
}
