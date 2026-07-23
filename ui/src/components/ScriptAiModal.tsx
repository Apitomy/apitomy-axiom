import { useEffect, useState } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    StackItem,
    Title,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { aiEditScript } from "../config/api";
import { AiEditModal } from "./AiEditModal";

interface ScriptAiModalProps {
    isOpen: boolean;
    script: string;
    actionTypeName?: string;
    actionTypeDescription?: string;
    onApply: (script: string) => void;
    onClose: () => void;
}

/**
 * Full-screen modal for AI-assisted script editing. Left side shows the
 * current script template in a code editor. Right side is a chat interface
 * for giving instructions to the AI.
 */
export function ScriptAiModal({
    isOpen, script, actionTypeName, actionTypeDescription, onApply, onClose,
}: ScriptAiModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [localScript, setLocalScript] = useState(script);

    useEffect(() => {
        if (isOpen) {
            setLocalScript(script);
        }
    }, [isOpen, script]);

    const handleSend = (message: string) =>
        aiEditScript({
            message,
            currentScript: localScript || undefined,
            actionTypeName,
            actionTypeDescription,
        }).then((response) => {
            if (response.script) {
                setLocalScript(response.script);
            }
            return { explanation: response.explanation || "Done." };
        });

    return (
        <AiEditModal
            isOpen={isOpen}
            onClose={onClose}
            onApply={() => onApply(localScript)}
            title="AI-Assisted Script Editor"
            placeholder="Describe what this script should do..."
            emptyHint="Describe what this script should do. The AI will generate a bash script with the appropriate Axiom API calls and placeholders."
            onSendMessage={handleSend}
        >
            <StackItem style={{ padding: "16px", paddingBottom: "8px" }}>
                <Title headingLevel="h4" size="md">
                    Script Template
                </Title>
                <div className="axiom-text-subtle" style={{ fontSize: "13px", marginTop: "4px" }}>
                    Available placeholders:{" "}
                    <code>{"{{projectId}}"}</code>,{" "}
                    <code>{"{{eventId}}"}</code>,{" "}
                    <code>{"{{taskId}}"}</code>,{" "}
                    <code>{"{{issueRef}}"}</code>,{" "}
                    <code>{"{{repository}}"}</code>,{" "}
                    <code>{"{{projectName}}"}</code>,{" "}
                    <code>{"{{managerInput}}"}</code>,{" "}
                    <code>{"{{apiBaseUrl}}"}</code>
                </div>
            </StackItem>
            <StackItem isFilled style={{ paddingLeft: "16px", paddingRight: "16px" }}>
                <CodeEditor
                    code={localScript || ""}
                    language={Language.shell}
                    isFullHeight
                    isDarkTheme={effectiveTheme === "dark"}
                    isReadOnly={false}
                    isLineNumbersVisible
                />
            </StackItem>
        </AiEditModal>
    );
}
