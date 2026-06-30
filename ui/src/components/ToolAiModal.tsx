import { useEffect, useState } from "react";
import {
    StackItem,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { aiEditTool, type NewToolDefinition, type ToolParameter } from "../config/api";
import { AiEditModal } from "./AiEditModal";

interface ToolAiModalProps {
    isOpen: boolean;
    form: NewToolDefinition;
    params: ToolParameter[];
    onApply: (form: Partial<NewToolDefinition>, params: ToolParameter[]) => void;
    onClose: () => void;
}

/**
 * Full-screen modal for AI-assisted tool editing. Left side shows the
 * current parameters and script template (read-only preview, updated live
 * by AI). Right side is a chat interface for giving instructions.
 */
export function ToolAiModal({ isOpen, form, params, onApply, onClose }: ToolAiModalProps) {
    const [localForm, setLocalForm] = useState(form);
    const [localParams, setLocalParams] = useState(params);

    useEffect(() => {
        if (isOpen) {
            setLocalForm(form);
            setLocalParams(params);
        }
    }, [isOpen, form, params]);

    const handleSend = (message: string) => {
        const currentTool: NewToolDefinition = {
            ...localForm,
            parameters: localParams.length > 0 ? localParams : undefined,
        };

        return aiEditTool({ message, currentTool }).then((response) => {
            if (response.tool) {
                setLocalForm((prev) => ({
                    ...prev,
                    name: response.tool!.name || prev.name,
                    description: response.tool!.description || prev.description,
                    scriptTemplate: response.tool!.scriptTemplate || prev.scriptTemplate,
                }));
                setLocalParams(response.tool!.parameters || []);
            }
            return { explanation: response.explanation || "Done." };
        });
    };

    return (
        <AiEditModal
            isOpen={isOpen}
            onClose={onClose}
            onApply={() => onApply(
                {
                    name: localForm.name,
                    description: localForm.description,
                    scriptTemplate: localForm.scriptTemplate,
                },
                localParams,
            )}
            title="AI-Assisted Tool Editor"
            placeholder="Describe what this tool should do..."
            emptyHint="Describe the tool you want to create or how you'd like to modify it. The AI will generate the parameters and script template."
            onSendMessage={handleSend}
        >
            <StackItem style={{ padding: "16px" }}>
                <Title headingLevel="h4" size="md" style={{ marginBottom: "8px" }}>
                    Parameters ({localParams.length})
                </Title>
                {localParams.length > 0 ? (
                    <div style={{ maxHeight: "200px", overflowY: "auto" }}>
                        <Table aria-label="Parameters" variant="compact">
                            <Thead>
                                <Tr>
                                    <Th>Name</Th>
                                    <Th>Type</Th>
                                    <Th>Description</Th>
                                    <Th>Required</Th>
                                </Tr>
                            </Thead>
                            <Tbody>
                                {localParams.map((p, i) => (
                                    <Tr key={i}>
                                        <Td><code>{p.name}</code></Td>
                                        <Td>{p.type}</Td>
                                        <Td>{p.description || "—"}</Td>
                                        <Td>{p.required ? "Yes" : "No"}</Td>
                                    </Tr>
                                ))}
                            </Tbody>
                        </Table>
                    </div>
                ) : (
                    <div style={{ color: "#6a6e73", fontSize: "13px" }}>
                        No parameters defined yet. Ask the AI to create them.
                    </div>
                )}
            </StackItem>
            <StackItem>
                <Title headingLevel="h4" size="md" style={{ paddingLeft: "16px" }}>
                    Script Template
                </Title>
            </StackItem>
            <StackItem isFilled style={{ paddingLeft: "16px", paddingRight: "16px" }}>
                <CodeEditor
                    code={localForm.scriptTemplate || ""}
                    language={Language.shell}
                    isFullHeight
                    isReadOnly={false}
                    isLineNumbersVisible
                />
            </StackItem>
        </AiEditModal>
    );
}
