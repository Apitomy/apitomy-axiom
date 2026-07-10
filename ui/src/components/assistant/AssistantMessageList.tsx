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
}

interface AssistantMessageListProps {
    messages: ChatMessage[];
    onPermissionRespond: (permissionId: string, allow: boolean, toolInput?: Record<string, unknown>) => void;
    isProcessing?: boolean;
    processingText?: string;
}

export function AssistantMessageList({ messages, onPermissionRespond, isProcessing, processingText }: AssistantMessageListProps) {
    const endRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        endRef.current?.scrollIntoView({ behavior: "smooth" });
    }, [messages.length, isProcessing]);

    return (
        <div style={{
            flex: "1 1 0",
            minHeight: 0,
            overflowY: "auto",
            padding: "16px",
        }}>
            {messages.map((msg) => {
                switch (msg.type) {
                    case "system":
                        return (
                            <div key={msg.id} style={{
                                padding: "8px 12px",
                                margin: "4px 0",
                                fontSize: "13px",
                                color: "#6a6e73",
                                fontStyle: "italic",
                            }}>
                                {msg.content}
                            </div>
                        );

                    case "warning":
                        return (
                            <div key={msg.id} style={{
                                padding: "8px 12px",
                                margin: "4px 8px",
                                fontSize: "13px",
                                color: "#795600",
                                backgroundColor: "#fdf7e7",
                                border: "1px solid #f0d67b",
                                borderRadius: "4px",
                                display: "flex",
                                alignItems: "center",
                                gap: "8px",
                            }}>
                                <ExclamationTriangleIcon color="#795600" />
                                {msg.content}
                            </div>
                        );

                    case "user":
                        return (
                            <div key={msg.id} style={{
                                display: "flex",
                                justifyContent: "flex-end",
                                margin: "8px 0",
                            }}>
                                <div style={{
                                    maxWidth: "80%",
                                    padding: "10px 14px",
                                    borderRadius: "12px 12px 2px 12px",
                                    backgroundColor: "#0066cc",
                                    color: "white",
                                    whiteSpace: "pre-wrap",
                                    fontSize: "14px",
                                }}>
                                    {msg.content}
                                </div>
                            </div>
                        );

                    case "assistant":
                        return (
                            <div key={msg.id} style={{
                                display: "flex",
                                justifyContent: "flex-start",
                                margin: "8px 0",
                            }}>
                                <div className="assistant-markdown" style={{
                                    maxWidth: "80%",
                                    padding: "10px 14px",
                                    borderRadius: "12px 12px 12px 2px",
                                    backgroundColor: "#f0f0f0",
                                    fontSize: "14px",
                                }}>
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
                                permissionId={msg.permissionId}
                                permissionResolved={msg.permissionResolved}
                                onPermissionRespond={onPermissionRespond}
                            />
                        );

                    case "thinking":
                        return (
                            <div key={msg.id} style={{
                                padding: "8px 12px",
                                margin: "4px 0",
                                fontSize: "13px",
                                color: "#6a6e73",
                                fontStyle: "italic",
                            }}>
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
                <div style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                    padding: "12px",
                    margin: "8px 0",
                    fontSize: "13px",
                    color: "#6a6e73",
                }}>
                    <Spinner size="md" />
                    <span>{processingText || "Claude is working..."}</span>
                </div>
            )}
            <div ref={endRef} />
        </div>
    );
}
