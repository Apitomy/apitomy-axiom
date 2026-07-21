import { useState, useEffect, useCallback, useRef } from "react";
import { Alert } from "@patternfly/react-core";
import { AssistantMessageList, type ChatMessage } from "./AssistantMessageList";
import { AssistantMessageInput } from "./AssistantMessageInput";
import {
    assistantEventsUrl,
    sendAssistantMessage,
    respondToAssistantPermission,
} from "../../config/api";

interface AssistantChatPanelProps {
    sessionId: string;
    onItemsChanged?: () => void;
}

const INITIAL_RECONNECT_DELAY = 1000;
const MAX_RECONNECT_DELAY = 30000;
const MAX_RECONNECT_ATTEMPTS = 10;

let messageIdCounter = 0;

export function AssistantChatPanel({ sessionId, onItemsChanged }: AssistantChatPanelProps) {
    const [messages, setMessages] = useState<ChatMessage[]>(() => [{
        id: String(++messageIdCounter),
        type: "assistant" as const,
        content: "Hi! I'm the **Axiom Configuration Assistant**. I can help you create and refine:\n\n" +
            "- **Tools** — script-based tools that AI agents can invoke\n" +
            "- **Action Types** — define kinds of work for AI agents or scripts\n" +
            "- **Report Definitions** — recurring or on-demand reports\n\n" +
            "I can look up your existing configuration to understand what's already set up. " +
            "Just describe what you'd like to create or ask me a question to get started!",
    }]);
    const [isProcessing, setIsProcessing] = useState(false);
    const [isReconnecting, setIsReconnecting] = useState(false);
    const eventSourceRef = useRef<EventSource | null>(null);
    const reconnectDelayRef = useRef(INITIAL_RECONNECT_DELAY);
    const reconnectAttemptsRef = useRef(0);
    const eventsReceivedRef = useRef(0);
    const eventsToSkipRef = useRef(0);
    const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const addMessage = useCallback((msg: Omit<ChatMessage, "id">) => {
        setMessages((prev) => [...prev, { ...msg, id: String(++messageIdCounter) }]);
    }, []);

    useEffect(() => {
        const url = assistantEventsUrl(sessionId);

        function connect() {
            const es = new EventSource(url);
            eventSourceRef.current = es;

            es.onopen = () => {
                reconnectDelayRef.current = INITIAL_RECONNECT_DELAY;
                reconnectAttemptsRef.current = 0;
                setIsReconnecting(false);
            };

            /**
             * Wraps an SSE event handler with replay-skip logic: on reconnect the
             * backend replays the full event history, so we skip events that were
             * already processed before the connection dropped.
             */
            function withSkipGuard(handler: (e: MessageEvent) => void): (e: MessageEvent) => void {
                return (e: MessageEvent) => {
                    eventsReceivedRef.current++;
                    if (eventsToSkipRef.current > 0) {
                        eventsToSkipRef.current--;
                        return;
                    }
                    handler(e);
                };
            }

            es.addEventListener("assistant_text", withSkipGuard((e) => {
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
                    }
                } catch {
                    // ignore
                }
            }));

            es.addEventListener("thinking", withSkipGuard(() => {
                setMessages((prev) => {
                    const last = prev[prev.length - 1];
                    if (last && last.type === "thinking") return prev;
                    return [...prev, { id: String(++messageIdCounter), type: "thinking" }];
                });
            }));

            es.addEventListener("tool_use", withSkipGuard((e) => {
                try {
                    const data = JSON.parse(e.data);
                    addMessage({
                        type: "tool_use",
                        toolName: data.name,
                        toolInput: data.input,
                        toolUseId: data.id,
                    });
                } catch {
                    // ignore
                }
            }));

            es.addEventListener("tool_result", withSkipGuard((e) => {
                try {
                    const data = JSON.parse(e.data);
                    setMessages((prev) =>
                        prev.map((m) =>
                            m.type === "tool_use" && m.toolUseId === data.toolUseId
                                ? { ...m, toolResult: data.stdout || data.stderr || "", isError: !!data.stderr && !data.stdout }
                                : m
                        )
                    );
                    onItemsChanged?.();
                } catch {
                    // ignore
                }
            }));

            es.addEventListener("permission_request", withSkipGuard((e) => {
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
                } catch {
                    // ignore
                }
            }));

            es.addEventListener("turn_complete", withSkipGuard(() => {
                setIsProcessing(false);
            }));

            es.addEventListener("session_error", withSkipGuard((e) => {
                try {
                    const data = JSON.parse(e.data);
                    addMessage({ type: "system", content: data.message || "Session error" });
                } catch {
                    // ignore
                }
                setIsProcessing(false);
            }));

            es.onerror = () => {
                es.close();
                if (reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
                    setIsReconnecting(true);
                    eventsToSkipRef.current = eventsReceivedRef.current;
                    reconnectTimerRef.current = setTimeout(() => {
                        reconnectAttemptsRef.current++;
                        reconnectDelayRef.current = Math.min(
                            reconnectDelayRef.current * 2, MAX_RECONNECT_DELAY
                        );
                        connect();
                    }, reconnectDelayRef.current);
                } else {
                    setIsReconnecting(false);
                    setIsProcessing(false);
                    addMessage({
                        type: "system",
                        content: "Connection lost. Please refresh the page to continue.",
                    });
                }
            };
        }

        connect();

        return () => {
            eventSourceRef.current?.close();
            eventSourceRef.current = null;
            if (reconnectTimerRef.current) {
                clearTimeout(reconnectTimerRef.current);
            }
        };
    }, [sessionId, addMessage, onItemsChanged]);

    const handleSend = useCallback(async (message: string) => {
        addMessage({ type: "user", content: message });
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
                    ? { ...m, permissionResolved: true }
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

    return (
        <div style={{
            display: "flex",
            flexDirection: "column",
            flex: "1 1 0",
            minHeight: 0,
        }}>
            <AssistantMessageList
                messages={messages}
                onPermissionRespond={handlePermissionRespond}
                isProcessing={isProcessing}
            />
            {isReconnecting && (
                <Alert variant="warning" isInline isPlain title="Connection lost. Reconnecting..." />
            )}
            <AssistantMessageInput onSend={handleSend} disabled={isProcessing} />
        </div>
    );
}
