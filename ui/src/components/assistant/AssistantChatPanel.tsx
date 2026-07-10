import { useState, useEffect, useCallback, useRef } from "react";
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

let messageIdCounter = 0;

export function AssistantChatPanel({ sessionId, onItemsChanged }: AssistantChatPanelProps) {
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [isProcessing, setIsProcessing] = useState(false);
    const [processingText, setProcessingText] = useState("");
    const eventSourceRef = useRef<EventSource | null>(null);

    const THINKING_MESSAGES = [
        "Claude is working...",
        "Pondering the mysteries of code...",
        "Consulting the silicon oracle...",
        "Rummaging through the codebase...",
        "Herding electrons...",
        "Untangling spaghetti...",
        "Asking the rubber duck...",
        "Brewing a fresh pot of logic...",
        "Warming up the flux capacitor...",
        "Reticulating splines...",
    ];

    const addMessage = useCallback((msg: Omit<ChatMessage, "id">) => {
        setMessages((prev) => [...prev, { ...msg, id: String(++messageIdCounter) }]);
    }, []);

    useEffect(() => {
        const url = assistantEventsUrl(sessionId);
        const es = new EventSource(url);
        eventSourceRef.current = es;

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
                }
            } catch {
                // ignore
            }
        });

        es.addEventListener("thinking", () => {
            setProcessingText(
                THINKING_MESSAGES[Math.floor(Math.random() * THINKING_MESSAGES.length)]
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
                onItemsChanged?.();
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
            } catch {
                // ignore
            }
        });

        es.addEventListener("permission_resolved", (e) => {
            try {
                const data = JSON.parse(e.data);
                setMessages((prev) =>
                    prev.map((m) =>
                        m.permissionId === data.permissionId
                            ? { ...m, permissionResolved: true }
                            : m
                    )
                );
            } catch {
                // ignore
            }
        });

        es.addEventListener("turn_complete", () => {
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
            if (es.readyState === EventSource.CLOSED) {
                setIsProcessing(false);
            }
        };

        return () => {
            es.close();
            eventSourceRef.current = null;
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
                processingText={processingText}
            />
            <AssistantMessageInput onSend={handleSend} disabled={isProcessing} />
        </div>
    );
}
