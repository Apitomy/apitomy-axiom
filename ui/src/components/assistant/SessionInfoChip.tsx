import { useRef, useState } from "react";
import { Content, Label, Popover } from "@patternfly/react-core";
import { Table, Tbody, Td, Tr } from "@patternfly/react-table";
import { RobotIcon } from "@patternfly/react-icons";

/** Props for {@link SessionInfoChip}. */
export interface SessionInfoChipProps {
    /** Engine display name, e.g. "Claude Code". */
    engineLabel?: string;
    /** Engine id, e.g. "claude-code". */
    engine?: string;
    /** Model the session was configured with; absent when the engine's default model is used. */
    requestedModel?: string;
    /** Model the engine reported actually running, once known. */
    actualModel?: string | null;
    /** Cumulative session cost in USD; zero or less when the engine doesn't report a cost. */
    costUsd: number;
    inputTokens: number;
    outputTokens: number;
}

/**
 * Short text for the chip: the session cost (e.g. `$0.1234`), else the total token count, else nothing.
 *
 * @param costUsd      cumulative cost in USD
 * @param inputTokens  input tokens
 * @param outputTokens output tokens
 * @return the chip text, or an empty string when there is no usage yet
 */
export function sessionInfoChipText(costUsd: number, inputTokens: number, outputTokens: number): string {
    const totalTokens: number = inputTokens + outputTokens;
    if (costUsd > 0) {
        return `$${costUsd.toFixed(4)}`;
    }
    return totalTokens > 0 ? `${totalTokens.toLocaleString()} tokens` : "";
}

/**
 * One compact chip summarising an AI session (a robot icon plus the cost or token count). Hovering or clicking
 * it shows the full details: engine, requested and actual model, cost and input/output tokens. Clicking pins
 * the details open until clicked again or dismissed.
 */
export function SessionInfoChip(props: SessionInfoChipProps) {
    const { engineLabel, engine, requestedModel, actualModel, costUsd, inputTokens, outputTokens } = props;
    const [hovered, setHovered] = useState<boolean>(false);
    const [pinned, setPinned] = useState<boolean>(false);
    const triggerRef = useRef<HTMLSpanElement>(null);
    const text: string = sessionInfoChipText(costUsd, inputTokens, outputTokens);
    const engineText: string | undefined = engineLabel || engine;

    const rows: Array<[string, string]> = [
        ...(engineText
            ? [["Engine", `${engineText}${engine && engineLabel && engine !== engineLabel ? ` (${engine})` : ""}`]
            ] as Array<[string, string]>
            : []),
        ["Requested model", requestedModel || "Engine default"],
        ["Actual model", actualModel || "Not reported yet"],
        ["Cost", costUsd > 0 ? `$${costUsd.toFixed(4)}` : "Not reported"],
        ["Tokens", `${inputTokens.toLocaleString()} in / ${outputTokens.toLocaleString()} out`],
    ];

    // A striped, header-less two-column table: label on the left, value on the right.
    const details = (
        <Table aria-label="AI session details" variant="compact" isStriped
            className="axiom-session-info-chip__table">
            <Tbody>
                {rows.map(([label, value]) => (
                    <Tr key={label}>
                        <Td className="axiom-session-info-chip__label" modifier="nowrap">{label}</Td>
                        <Td>{value}</Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );

    // A click on the chip while it is only hover-open pins it instead of closing it.
    const shouldClose = (event?: MouseEvent | KeyboardEvent): void => {
        const onTrigger: boolean = Boolean(event?.target instanceof Node
            && triggerRef.current?.contains(event.target));
        if (onTrigger && event?.type === "click" && !pinned) {
            setPinned(true);
            return;
        }
        setPinned(false);
        setHovered(false);
    };

    return (
        <Popover aria-label="AI session details" headerContent={<Content component="h6">AI session</Content>}
            bodyContent={details} position="bottom-end" isVisible={pinned || hovered}
            shouldOpen={() => setPinned(true)} shouldClose={shouldClose}>
            <span ref={triggerRef} className="axiom-session-info-chip" role="button" tabIndex={0}
                aria-label={`AI session details${text ? `: ${text}` : ""}`}
                onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}>
                <Label color="grey" isCompact icon={<RobotIcon />}>{text || "AI"}</Label>
            </span>
        </Popover>
    );
}
