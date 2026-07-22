import type { ReactNode } from "react";
import {
    Button,
    Flex,
    FlexItem,
} from "@patternfly/react-core";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import { AddToolInput } from "./AddToolInput";

/**
 * Editable list of tool patterns with add/remove and @-prefix toolset styling.
 *
 * Used by ActionTypeDetailPage, ReportDefinitionDetailPage, and ToolsetDetailPage.
 */
export function ToolListEditor({ tools, onAdd, onRemove, onReplace, helpText, emptyContent }: {
    tools: string[];
    onAdd: (tool: string) => void;
    onRemove: (tool: string) => void;
    onReplace: (tools: string[]) => void;
    helpText?: ReactNode;
    emptyContent?: ReactNode;
}) {
    return (
        <div style={{ maxWidth: "700px" }}>
            {helpText && (
                <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                    {helpText}
                </p>
            )}

            <div style={{ marginBottom: "16px" }}>
                <AddToolInput onAdd={onAdd} onReplace={onReplace} existingTools={tools} />
            </div>

            {tools.length === 0 ? (
                emptyContent ?? null
            ) : (
                <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                    {tools.map((tool) => (
                        <Flex
                            key={tool}
                            alignItems={{ default: "alignItemsCenter" }}
                            style={{
                                padding: "8px 12px",
                                backgroundColor: tool.startsWith("@")
                                    ? "var(--pf-t--global--background--color--primary--default)"
                                    : "var(--pf-t--global--background--color--secondary--default)",
                                borderRadius: "4px",
                                border: tool.startsWith("@")
                                    ? "1px solid var(--pf-t--global--border--color--default)"
                                    : "none",
                            }}
                        >
                            <FlexItem grow={{ default: "grow" }}>
                                <code style={{
                                    fontSize: "13px",
                                    color: tool.startsWith("@")
                                        ? "var(--pf-t--global--color--brand--default)"
                                        : "inherit",
                                }}>{tool}</code>
                            </FlexItem>
                            <FlexItem>
                                <Button
                                    variant="plain"
                                    size="sm"
                                    onClick={() => onRemove(tool)}
                                    aria-label={`Remove ${tool}`}
                                >
                                    <TimesIcon />
                                </Button>
                            </FlexItem>
                        </Flex>
                    ))}
                </div>
            )}
        </div>
    );
}
