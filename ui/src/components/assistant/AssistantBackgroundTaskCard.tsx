import { memo } from "react";
import { Button, Spinner } from "@patternfly/react-core";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import "./AssistantBackgroundTaskCard.css";

export interface BackgroundTaskCardData {
    id: string;
    taskId: string;
    description: string;
    status: "running" | "completed";
    durationMs: number;
    dismissed: boolean;
}

interface AssistantBackgroundTaskCardProps {
    card: BackgroundTaskCardData;
    onDismiss: (id: string) => void;
}

export const AssistantBackgroundTaskCard = memo(function AssistantBackgroundTaskCard({
    card, onDismiss,
}: AssistantBackgroundTaskCardProps) {
    const isComplete = card.status === "completed";

    return (
        <div className="axiom-bg-task-card" data-status={card.status}>
            <div className="axiom-bg-task-card__header">
                <span className="axiom-bg-task-card__description">
                    {card.description}
                </span>
                {isComplete && (
                    <Button
                        variant="plain"
                        size="sm"
                        aria-label="Dismiss"
                        className="axiom-bg-task-card__close-btn"
                        onClick={() => onDismiss(card.id)}
                    >
                        <TimesIcon />
                    </Button>
                )}
            </div>
            <div className="axiom-bg-task-card__status">
                {isComplete ? (
                    <span className="axiom-bg-task-card__completed">
                        <CheckCircleIcon />
                        Completed in {formatDuration(card.durationMs)}
                    </span>
                ) : (
                    <span className="axiom-bg-task-card__running">
                        <Spinner size="sm" />
                        <span>Running...</span>
                        <span className="axiom-bg-task-card__elapsed">
                            {formatDuration(card.durationMs)}
                        </span>
                    </span>
                )}
            </div>
        </div>
    );
});

function formatDuration(ms: number): string {
    const seconds = Math.round(ms / 1000);
    if (seconds < 60) return `${seconds}s`;
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return secs > 0 ? `${mins}m ${secs}s` : `${mins}m`;
}
