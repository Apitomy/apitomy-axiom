import { useEffect, useState } from "react";
import {
    Flex,
    FlexItem,
    StackItem,
    Title,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { aiEditReportPrompt } from "../config/api";
import { AiEditModal } from "./AiEditModal";

interface ReportAiModalProps {
    isOpen: boolean;
    promptTemplate: string;
    allowedTools: string[];
    reportName?: string;
    reportDescription?: string;
    onApply: (promptTemplate: string, allowedTools: string[]) => void;
    onClose: () => void;
}

/**
 * Full-screen modal for AI-assisted report definition editing.
 * Left side shows allowed tools and the prompt template preview.
 * Right side is a chat interface for giving instructions to the AI.
 */
export function ReportAiModal({
    isOpen, promptTemplate, allowedTools, reportName, reportDescription,
    onApply, onClose,
}: ReportAiModalProps) {
    const [localPrompt, setLocalPrompt] = useState(promptTemplate);
    const [localTools, setLocalTools] = useState(allowedTools);

    useEffect(() => {
        if (isOpen) {
            setLocalPrompt(promptTemplate);
            setLocalTools(allowedTools);
        }
    }, [isOpen, promptTemplate, allowedTools]);

    const handleSend = (message: string) =>
        aiEditReportPrompt({
            message,
            currentPromptTemplate: localPrompt || undefined,
            currentAllowedTools: localTools.length > 0 ? localTools : undefined,
            reportName,
            reportDescription,
        }).then((response) => {
            if (response.promptTemplate) {
                setLocalPrompt(response.promptTemplate);
            }
            if (Array.isArray(response.allowedTools)) {
                setLocalTools(response.allowedTools);
            } else if (typeof response.allowedTools === "string") {
                setLocalTools((response.allowedTools as string).split(",").map((s: string) => s.trim()).filter(Boolean));
            }
            return { explanation: response.explanation || "Done." };
        });

    return (
        <AiEditModal
            isOpen={isOpen}
            onClose={onClose}
            onApply={() => onApply(localPrompt, localTools)}
            title="AI-Assisted Report Editor"
            placeholder="Describe what this report should cover..."
            emptyHint="Describe what this report should cover. The AI will generate a prompt template and recommend the appropriate tools."
            onSendMessage={handleSend}
        >
            <StackItem style={{ padding: "16px", paddingBottom: "8px" }}>
                <Title headingLevel="h4" size="md" style={{ marginBottom: "8px" }}>
                    Allowed Tools ({localTools.length})
                </Title>
                {localTools.length > 0 ? (
                    <div style={{ display: "flex", flexWrap: "wrap", gap: "4px",
                        maxHeight: "80px", overflowY: "auto" }}>
                        {localTools.map((tool) => (
                            <Flex key={tool}
                                alignItems={{ default: "alignItemsCenter" }}
                                style={{
                                    padding: "4px 8px",
                                    backgroundColor: tool.startsWith("@")
                                        ? "var(--pf-t--global--background--color--primary--default)"
                                        : "var(--pf-t--global--background--color--secondary--default)",
                                    borderRadius: "4px",
                                    border: tool.startsWith("@")
                                        ? "1px solid var(--pf-t--global--border--color--default)"
                                        : "none",
                                }}>
                                <FlexItem>
                                    <code style={{
                                        fontSize: "12px",
                                        color: tool.startsWith("@")
                                            ? "var(--pf-t--global--color--brand--default)"
                                            : "inherit",
                                    }}>{tool}</code>
                                </FlexItem>
                            </Flex>
                        ))}
                    </div>
                ) : (
                    <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                        No tools configured. Ask the AI to recommend tools.
                    </div>
                )}
            </StackItem>
            <StackItem style={{ paddingLeft: "16px", paddingBottom: "4px" }}>
                <Title headingLevel="h4" size="md">
                    Prompt Template
                </Title>
                <div className="axiom-text-subtle" style={{ fontSize: "13px", marginTop: "2px" }}>
                    Placeholders:{" "}
                    <code>{"{{repositories}}"}</code>,{" "}
                    <code>{"{{timeRangeStart}}"}</code>,{" "}
                    <code>{"{{timeRangeEnd}}"}</code>,{" "}
                    <code>{"{{timeWindow}}"}</code>
                </div>
            </StackItem>
            <StackItem isFilled style={{ paddingLeft: "16px", paddingRight: "16px" }}>
                <CodeEditor
                    code={localPrompt || ""}
                    language={Language.markdown}
                    isFullHeight
                    isReadOnly={false}
                    isLineNumbersVisible
                />
            </StackItem>
        </AiEditModal>
    );
}
