import { Label, Tooltip } from "@patternfly/react-core";

/** Props for {@link SessionUsageLabel}. */
export interface SessionUsageLabelProps {
    /** Cumulative session cost in USD; zero or less when the engine doesn't report a cost. */
    costUsd: number;
    inputTokens: number;
    outputTokens: number;
    className?: string;
}

/** Tooltip text listing a session's input and output token counts. */
export function sessionUsageTooltip(inputTokens: number, outputTokens: number): string {
    return `Tokens in: ${inputTokens.toLocaleString()} / out: ${outputTokens.toLocaleString()}`;
}

/**
 * Compact label showing an AI session's cost (e.g. `$0.1234`), or its total token count when no cost is
 * reported, with a tooltip of the input/output token counts. Renders nothing when there is no usage yet.
 */
export function SessionUsageLabel({ costUsd, inputTokens, outputTokens, className }: SessionUsageLabelProps) {
    const totalTokens: number = inputTokens + outputTokens;
    if (costUsd <= 0 && totalTokens <= 0) {
        return null;
    }
    const text: string = costUsd > 0 ? `$${costUsd.toFixed(4)}` : `${totalTokens.toLocaleString()} tokens`;
    return (
        <Tooltip content={sessionUsageTooltip(inputTokens, outputTokens)}>
            <Label color="grey" isCompact className={className}>{text}</Label>
        </Tooltip>
    );
}
