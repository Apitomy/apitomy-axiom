import { useEffect, useRef } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Content, Spinner } from "@patternfly/react-core";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import { AssistantToolUseBlock } from "./AssistantToolUseBlock";
import { AssistantPermissionPrompt } from "./AssistantPermissionPrompt";
import "./AssistantMessageList.css";

export interface ChatMessage {
    id: string;
    type: "system" | "warning" | "user" | "assistant" | "tool_use" | "tool_result" | "permission_request" | "thinking";
    content?: string;
    toolName?: string;
    toolInput?: Record<string, unknown>;
    toolUseId?: string;
    toolResult?: string;
    isError?: boolean;
    permissionId?: string;
    permissionResolved?: boolean;
    permissionAllowed?: boolean;
    elapsedSeconds?: number;
}

interface AssistantMessageListProps {
    messages: ChatMessage[];
    onPermissionRespond: (permissionId: string, allow: boolean, toolInput?: Record<string, unknown>) => void;
    onCreateAutoApproval?: (toolName: string, fieldName: string | undefined,
        pattern: string | undefined, permissionId: string) => void;
    isProcessing?: boolean;
    processingText?: string;
}

export function AssistantMessageList({ messages, onPermissionRespond, onCreateAutoApproval, isProcessing, processingText }: AssistantMessageListProps) {
    const endRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        endRef.current?.scrollIntoView({ behavior: "auto" });
    }, [messages.length]);

    return (
        <div className="axiom-message-list">
            {messages.map((msg) => {
                switch (msg.type) {
                    case "system":
                        return (
                            <div key={msg.id} className="axiom-message-list__system">
                                {msg.content}
                            </div>
                        );

                    case "warning":
                        return (
                            <div key={msg.id} className="axiom-message-list__warning">
                                <ExclamationTriangleIcon className="axiom-message-list__warning-icon" />
                                {msg.content}
                            </div>
                        );

                    case "user":
                        return (
                            <div key={msg.id} className="axiom-message-list__user-row">
                                <div className="axiom-message-list__user-bubble">
                                    {msg.content}
                                </div>
                            </div>
                        );

                    case "assistant":
                        return (
                            <div key={msg.id} className="axiom-message-list__assistant-row">
                                <div className="assistant-markdown axiom-message-list__assistant-bubble">
                                    <Content>
                                        <Markdown remarkPlugins={[remarkGfm]}>
                                            {msg.content?.trim() || ""}
                                        </Markdown>
                                    </Content>
                                </div>
                            </div>
                        );

                    case "tool_use":
                        return (
                            <AssistantToolUseBlock
                                key={msg.id}
                                toolName={msg.toolName || "unknown"}
                                input={msg.toolInput}
                                result={msg.toolResult}
                                isError={msg.isError}
                                elapsedSeconds={msg.elapsedSeconds}
                                permissionId={msg.permissionId}
                                permissionResolved={msg.permissionResolved}
                                permissionAllowed={msg.permissionAllowed}
                                onPermissionRespond={onPermissionRespond}
                                onCreateAutoApproval={onCreateAutoApproval}
                            />
                        );

                    case "thinking":
                        return (
                            <div key={msg.id} className="axiom-message-list__thinking">
                                Thinking...
                            </div>
                        );

                    case "permission_request":
                        return (
                            <AssistantPermissionPrompt
                                key={msg.id}
                                permissionId={msg.permissionId || ""}
                                toolName={msg.toolName || "unknown"}
                                input={msg.toolInput}
                                onRespond={onPermissionRespond}
                                resolved={msg.permissionResolved}
                            />
                        );

                    default:
                        return null;
                }
            })}
            {isProcessing && (
                <div className="axiom-message-list__processing">
                    <Spinner size="md" />
                    <span>{processingText || "Claude is working..."}</span>
                </div>
            )}
            <div ref={endRef} />
        </div>
    );
}
