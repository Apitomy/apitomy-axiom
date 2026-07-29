import { useEffect, useRef, useState } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button, Content, Spinner, Tooltip } from "@patternfly/react-core";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import CopyIcon from "@patternfly/react-icons/dist/esm/icons/copy-icon";
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
    const [copiedId, setCopiedId] = useState<string | null>(null);
    const copyTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

    const handleCopyMarkdown = (msgId: string, content: string) => {
        navigator.clipboard.writeText(content).then(() => {
            if (copyTimeoutRef.current) {
                clearTimeout(copyTimeoutRef.current);
            }
            setCopiedId(msgId);
            copyTimeoutRef.current = setTimeout(() => setCopiedId(null), 2000);
        }).catch((err) => {
            console.warn("Failed to copy to clipboard:", err);
        });
    };

    useEffect(() => {
        return () => {
            if (copyTimeoutRef.current) {
                clearTimeout(copyTimeoutRef.current);
            }
        };
    }, []);

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
                                    <div className="axiom-message-list__copy-btn">
                                        <Tooltip content={copiedId === msg.id ? "Copied!" : "Copy markdown"}>
                                            <Button
                                                variant="plain"
                                                size="sm"
                                                aria-label="Copy markdown"
                                                onClick={() => handleCopyMarkdown(msg.id, msg.content?.trim() || "")}
                                            >
                                                {copiedId === msg.id
                                                    ? <CheckCircleIcon color="var(--pf-t--global--color--status--success--default)" />
                                                    : <CopyIcon />}
                                            </Button>
                                        </Tooltip>
                                    </div>
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
