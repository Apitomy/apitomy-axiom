import { useState, useEffect, useCallback, useRef } from "react";
import { Alert } from "@patternfly/react-core";
import { AssistantMessageList, type ChatMessage } from "./AssistantMessageList";
import { AssistantMessageInput } from "./AssistantMessageInput";
import {
    assistantEventsUrl,
    sendAssistantMessage,
    respondToAssistantPermission,
    createAutoApproval,
} from "../../config/api";
import { randomThinkingMessage } from "./thinkingMessages";

export type SessionMode = "normal" | "plan";

interface AssistantChatPanelProps {
    sessionId: string;
    onItemsChanged?: () => void;
    onModeChange?: (mode: SessionMode) => void;
    onAutoApprovalCountChange?: () => void;
    onModelDetected?: (model: string) => void;
    onCostUpdate?: (costUsd: number, inputTokens: number, outputTokens: number) => void;
}

let messageIdCounter = 0;

export function AssistantChatPanel({ sessionId, onItemsChanged, onModeChange, onAutoApprovalCountChange, onModelDetected, onCostUpdate }: AssistantChatPanelProps) {
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [isProcessing, setIsProcessing] = useState(false);
    const [processingText, setProcessingText] = useState("");
    const [slashCommands, setSlashCommands] = useState<string[]>([]);
    const [connectionLost, setConnectionLost] = useState(false);
    const eventSourceRef = useRef<EventSource | null>(null);
    const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const reconnectDelayRef = useRef(1000);
    const sessionEndedRef = useRef(false);

    // Keep callback refs so the EventSource effect doesn't re-run when
    // parent-supplied callbacks change identity (e.g. inline arrow functions).
    const onItemsChangedRef = useRef(onItemsChanged);
    const onModeChangeRef = useRef(onModeChange);
    const onModelDetectedRef = useRef(onModelDetected);
    const onCostUpdateRef = useRef(onCostUpdate);
    useEffect(() => { onItemsChangedRef.current = onItemsChanged; }, [onItemsChanged]);
    useEffect(() => { onModeChangeRef.current = onModeChange; }, [onModeChange]);
    useEffect(() => { onModelDetectedRef.current = onModelDetected; }, [onModelDetected]);
    useEffect(() => { onCostUpdateRef.current = onCostUpdate; }, [onCostUpdate]);

    const addMessage = useCallback((msg: Omit<ChatMessage, "id">) => {
        setMessages((prev) => [...prev, { ...msg, id: String(++messageIdCounter) }]);
    }, []);

    useEffect(() => {
        if ("Notification" in window && Notification.permission === "default") {
            Notification.requestPermission();
        }
    }, []);

    useEffect(() => {
        sessionEndedRef.current = false;
        setConnectionLost(false);
        reconnectDelayRef.current = 1000;

        const url = assistantEventsUrl(sessionId);

        function connect() {
            setMessages([]);

            const es = new EventSource(url);
            eventSourceRef.current = es;

            es.onopen = () => {
                setConnectionLost(false);
                reconnectDelayRef.current = 1000;
            };

            es.addEventListener("session_init", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    if (Array.isArray(data.slashCommands)) {
                        setSlashCommands(data.slashCommands);
                    }
                    if (data.model) {
                        onModelDetectedRef.current?.(data.model);
                    }
                } catch {
                    // ignore
                }
            });

            es.addEventListener("assistant_text", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    if (data.text) {
                        setMessages((prev) => {
                            const last = prev[prev.length - 1];
                            if (last && last.type === "assistant") {
                                return [...prev.slice(0, -1), { ...last, content: data.text }];
                            }
                            return [...prev, { id: String(++messageIdCounter), type: "assistant", content: data.text }];
                        });
                        setProcessingText(randomThinkingMessage());
                    }
                } catch {
                    // ignore
                }
            });

            es.addEventListener("user_message", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    if (data.content) {
                        setMessages((prev) => {
                            const last = prev[prev.length - 1];
                            if (last && last.type === "user" && last.content === data.content) {
                                return prev;
                            }
                            return [...prev, { id: String(++messageIdCounter), type: "user", content: data.content }];
                        });
                        setIsProcessing(true);
                    }
                } catch {
                    // ignore
                }
            });

            es.addEventListener("thinking", () => {
                setProcessingText(
                    randomThinkingMessage()
                );
            });

            es.addEventListener("tool_use", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    addMessage({
                        type: "tool_use",
                        toolName: data.name,
                        toolInput: data.input,
                        toolUseId: data.id,
                    });
                    setProcessingText(randomThinkingMessage());
                    if (data.name === "EnterPlanMode") {
                        onModeChangeRef.current?.("plan");
                    }
                } catch {
                    // ignore
                }
            });

            es.addEventListener("tool_result", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    setMessages((prev) =>
                        prev.map((m) =>
                            m.type === "tool_use" && m.toolUseId === data.toolUseId
                                ? { ...m, toolResult: data.stdout || data.stderr || "", isError: !!data.stderr && !data.stdout }
                                : m
                        )
                    );
                    onItemsChangedRef.current?.();
                } catch {
                    // ignore
                }
            });

            es.addEventListener("permission_request", (e) => {
                try {
                    const data = JSON.parse(e.data);

                    // Attach permission to the matching tool_use block
                    setMessages((prev) => {
                        const lastToolIdx = prev.findLastIndex(
                            (m) => m.type === "tool_use" && m.toolName === data.toolName && !m.permissionId
                        );
                        if (lastToolIdx >= 0) {
                            const updated = [...prev];
                            updated[lastToolIdx] = {
                                ...updated[lastToolIdx],
                                permissionId: data.requestId,
                                permissionResolved: false,
                                toolInput: data.toolInput || updated[lastToolIdx].toolInput,
                            };
                            return updated;
                        }
                        // Fallback: add as standalone if no matching tool_use
                        return [...prev, {
                            id: String(++messageIdCounter),
                            type: "permission_request" as const,
                            permissionId: data.requestId,
                            toolName: data.toolName,
                            toolInput: data.toolInput,
                        }];
                    });
                    setIsProcessing(false);

                    if (document.hidden
                            && "Notification" in window
                            && Notification.permission === "granted") {
                        const toolName = data.toolName as string;
                        let title = "Action required";
                        let body = `${toolName} needs your approval`;
                        if (toolName === "ExitPlanMode") {
                            title = "Plan ready for review";
                            body = "An assistant plan is waiting for your approval.";
                        } else if (toolName === "AskUserQuestion") {
                            title = "Question from assistant";
                            body = "The assistant is asking you a question.";
                        }
                        const notification = new Notification(title, {
                            body,
                            tag: `axiom-permission-${data.requestId}`,
                        });
                        notification.onclick = () => {
                            window.focus();
                            notification.close();
                        };
                    }
                } catch {
                    // ignore
                }
            });

            es.addEventListener("permission_resolved", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    setMessages((prev) => {
                        const match = prev.find((m) => m.permissionId === data.permissionId);
                        if (match?.toolName === "ExitPlanMode") {
                            onModeChangeRef.current?.("normal");
                        }
                        return prev.map((m) =>
                            m.permissionId === data.permissionId
                                ? { ...m, permissionResolved: true, permissionAllowed: data.allow }
                                : m
                        );
                    });
                } catch {
                    // ignore
                }
            });

            es.addEventListener("turn_complete", (e) => {
                setIsProcessing(false);
                try {
                    const data = JSON.parse(e.data);
                    if (data.costUsd != null) {
                        onCostUpdateRef.current?.(data.costUsd, data.inputTokens ?? 0, data.outputTokens ?? 0);
                    }
                } catch {
                    // ignore
                }
            });

            es.addEventListener("session_ended", () => {
                sessionEndedRef.current = true;
                setIsProcessing(false);
            });

            es.addEventListener("unhandled_event", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    addMessage({
                        type: "warning",
                        content: `Unhandled event type: ${data.rawType}`,
                    });
                } catch {
                    // ignore
                }
            });

            es.addEventListener("session_error", (e) => {
                try {
                    const data = JSON.parse(e.data);
                    addMessage({ type: "system", content: data.message || "Session error" });
                } catch {
                    // ignore
                }
                setIsProcessing(false);
            });

            es.onerror = () => {
                es.close();
                eventSourceRef.current = null;
                if (sessionEndedRef.current) return;
                setConnectionLost(true);
                setIsProcessing(false);
                reconnectTimerRef.current = setTimeout(() => {
                    reconnectTimerRef.current = null;
                    connect();
                }, reconnectDelayRef.current);
                reconnectDelayRef.current = Math.min(reconnectDelayRef.current * 2, 30000);
            };
        }

        connect();

        return () => {
            eventSourceRef.current?.close();
            eventSourceRef.current = null;
            if (reconnectTimerRef.current) {
                clearTimeout(reconnectTimerRef.current);
                reconnectTimerRef.current = null;
            }
        };
    }, [sessionId, addMessage]);

    const handleSend = useCallback(async (message: string) => {
        addMessage({ type: "user", content: message });
        setProcessingText(
            randomThinkingMessage()
        );
        setIsProcessing(true);
        try {
            await sendAssistantMessage(sessionId, message);
        } catch (err) {
            console.error("Failed to send message:", err);
            setIsProcessing(false);
        }
    }, [sessionId, addMessage]);

    const handlePermissionRespond = useCallback(async (
        permissionId: string, allow: boolean, toolInput?: Record<string, unknown>
    ) => {
        setMessages((prev) =>
            prev.map((m) =>
                m.permissionId === permissionId
                    ? { ...m, permissionResolved: true, permissionAllowed: allow }
                    : m
            )
        );
        setIsProcessing(true);
        try {
            await respondToAssistantPermission(sessionId, permissionId, allow, toolInput);
        } catch (err) {
            console.error("Failed to respond to permission:", err);
            setIsProcessing(false);
        }
    }, [sessionId]);

    const handleCreateAutoApproval = useCallback(async (
        toolName: string, fieldName: string | undefined,
        pattern: string | undefined, permissionId: string
    ) => {
        try {
            await createAutoApproval(sessionId, {
                toolName, fieldName, pattern, permissionId,
            });
            setMessages((prev) =>
                prev.map((m) =>
                    m.permissionId === permissionId
                        ? { ...m, permissionResolved: true, permissionAllowed: true }
                        : m
                )
            );
            setIsProcessing(true);
            onAutoApprovalCountChange?.();
        } catch (err) {
            console.error("Failed to create auto-approval:", err);
        }
    }, [sessionId, onAutoApprovalCountChange]);

    return (
        <div style={{
            display: "flex",
            flexDirection: "column",
            flex: "1 1 0",
            minHeight: 0,
        }}>
            {connectionLost && (
                <Alert variant="warning" isInline isPlain
                    title="Connection lost — reconnecting..."
                    style={{ flexShrink: 0 }} />
            )}
            <AssistantMessageList
                messages={messages}
                onPermissionRespond={handlePermissionRespond}
                onCreateAutoApproval={handleCreateAutoApproval}
                isProcessing={isProcessing}
                processingText={processingText}
            />
            <AssistantMessageInput onSend={handleSend} disabled={isProcessing} slashCommands={slashCommands} />
        </div>
    );
}
