import { useEffect, useState } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    Flex,
    FlexItem,
    StackItem,
    Title,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { aiEditActionPrompt } from "../config/api";
import { AiEditModal } from "./AiEditModal";

interface ActionTypeAiModalProps {
    isOpen: boolean;
    promptTemplate: string;
    allowedTools: string[];
    actionTypeName?: string;
    actionTypeDescription?: string;
    onApply: (promptTemplate: string, allowedTools: string[]) => void;
    onClose: () => void;
}

/**
 * Full-screen modal for AI-assisted action type editing. Left side shows
 * allowed tools and the prompt template preview. Right side is a chat
 * interface for giving instructions to the AI.
 */
export function ActionTypeAiModal({
    isOpen, promptTemplate, allowedTools, actionTypeName, actionTypeDescription,
    onApply, onClose,
}: ActionTypeAiModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [localPrompt, setLocalPrompt] = useState(promptTemplate);
    const [localTools, setLocalTools] = useState(allowedTools);

    useEffect(() => {
        if (isOpen) {
            setLocalPrompt(promptTemplate);
            setLocalTools(allowedTools);
        }
    }, [isOpen, promptTemplate, allowedTools]);

    const handleSend = (message: string) =>
        aiEditActionPrompt({
            message,
            currentPromptTemplate: localPrompt || undefined,
            currentAllowedTools: localTools.length > 0 ? localTools : undefined,
            reportName: actionTypeName,
            reportDescription: actionTypeDescription,
        }).then((response) => {
            if (response.promptTemplate) {
                setLocalPrompt(response.promptTemplate);
            }
            if (Array.isArray(response.allowedTools)) {
                setLocalTools(response.allowedTools);
            }
            return { explanation: response.explanation || "Done." };
        });

    return (
        <AiEditModal
            isOpen={isOpen}
            onClose={onClose}
            onApply={() => onApply(localPrompt, localTools)}
            title="AI-Assisted Action Type Editor"
            placeholder="Describe what this action type should do..."
            emptyHint="Describe what this action type should do. The AI will generate a prompt template and recommend the appropriate tools."
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
                    <code>{"{{managerInput}}"}</code>,{" "}
                    <code>{"{{issueRef}}"}</code>,{" "}
                    <code>{"{{repository}}"}</code>,{" "}
                    <code>{"{{projectName}}"}</code>
                </div>
            </StackItem>
            <StackItem isFilled style={{ paddingLeft: "16px", paddingRight: "16px" }}>
                <CodeEditor
                    code={localPrompt || ""}
                    language={Language.markdown}
                    isFullHeight
                    isDarkTheme={effectiveTheme === "dark"}
                    isReadOnly={false}
                    isLineNumbersVisible
                />
            </StackItem>
        </AiEditModal>
    );
}
