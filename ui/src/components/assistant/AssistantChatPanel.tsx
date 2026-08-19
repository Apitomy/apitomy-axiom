import { useState, useEffect, useCallback, useRef } from "react";
import { AssistantMessageList, type ChatMessage } from "./AssistantMessageList";
import { AssistantMessageInput } from "./AssistantMessageInput";
import { AssistantSubagentPanel } from "./AssistantSubagentPanel";
import type { SubagentCardData, SubagentActivityEntry } from "./AssistantSubagentCard";
import {
    sendAssistantMessage,
    respondToAssistantPermission,
    createAutoApproval,
    fetchAssistantSessionHistory,
} from "../../config/api";
import { sseClient } from "../../config/sse";
import { randomThinkingMessage } from "./thinkingMessages";

export type SessionMode = "normal" | "plan";

interface AssistantChatPanelProps {
    sessionId: string;
    onItemsChanged?: () => void;
    onModeChange?: (mode: SessionMode) => void;
    onAutoApprovalCountChange?: () => void;
    onAllowAllChanged?: (enabled: boolean) => void;
    onModelDetected?: (model: string) => void;
    onCostUpdate?: (costUsd: number, inputTokens: number, outputTokens: number) => void;
}

let messageIdCounter = 0;

export function AssistantChatPanel({ sessionId, onItemsChanged, onModeChange, onAutoApprovalCountChange, onAllowAllChanged, onModelDetected, onCostUpdate }: AssistantChatPanelProps) {
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [isProcessing, setIsProcessing] = useState(false);
    const [processingText, setProcessingText] = useState("");
    const [slashCommands, setSlashCommands] = useState<string[]>([]);
    const [subagentCards, setSubagentCards] = useState<Map<string, SubagentCardData>>(new Map());
    const sessionEndedRef = useRef(false);
    const lastSeenIndexRef = useRef(-1);

    const onItemsChangedRef = useRef(onItemsChanged);
    const onModeChangeRef = useRef(onModeChange);
    const onModelDetectedRef = useRef(onModelDetected);
    const onAllowAllChangedRef = useRef(onAllowAllChanged);
    const onCostUpdateRef = useRef(onCostUpdate);
    useEffect(() => { onItemsChangedRef.current = onItemsChanged; }, [onItemsChanged]);
    useEffect(() => { onModeChangeRef.current = onModeChange; }, [onModeChange]);
    useEffect(() => { onAllowAllChangedRef.current = onAllowAllChanged; }, [onAllowAllChanged]);
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

    const processEvent = useCallback((eventType: string, data: Record<string, unknown>) => {
        switch (eventType) {
            case "session_init":
                if (Array.isArray(data.slashCommands)) {
                    setSlashCommands(data.slashCommands as string[]);
                }
                if (data.model) {
                    onModelDetectedRef.current?.(data.model as string);
                }
                break;

            case "assistant_text":
                if (data.text) {
                    setMessages((prev) => {
                        const last = prev[prev.length - 1];
                        if (last && last.type === "assistant") {
                            return [...prev.slice(0, -1), { ...last, content: data.text as string }];
                        }
                        return [...prev, { id: String(++messageIdCounter), type: "assistant", content: data.text as string }];
                    });
                    setProcessingText(randomThinkingMessage());
                }
                break;

            case "user_message":
                if (data.content) {
                    setMessages((prev) => {
                        const last = prev[prev.length - 1];
                        if (last && last.type === "user" && last.content === data.content) {
                            return prev;
                        }
                        return [...prev, { id: String(++messageIdCounter), type: "user", content: data.content as string }];
                    });
                    setIsProcessing(true);
                }
                break;

            case "thinking":
                setProcessingText(randomThinkingMessage());
                break;

            case "tool_use":
                addMessage({
                    type: "tool_use",
                    toolName: data.name as string,
                    toolInput: data.input as Record<string, unknown>,
                    toolUseId: data.id as string,
                });
                setProcessingText(randomThinkingMessage());
                if (data.name === "EnterPlanMode") {
                    onModeChangeRef.current?.("plan");
                }
                break;

            case "tool_result":
                setMessages((prev) =>
                    prev.map((m) =>
                        m.type === "tool_use" && m.toolUseId === data.toolUseId
                            ? { ...m, toolResult: (data.stdout || data.stderr || "") as string, isError: !!data.stderr && !data.stdout }
                            : m
                    )
                );
                onItemsChangedRef.current?.();
                break;

            case "permission_request":
                setMessages((prev) => {
                    const lastToolIdx = prev.findLastIndex(
                        (m) => m.type === "tool_use" && m.toolName === data.toolName && !m.permissionId
                    );
                    if (lastToolIdx >= 0) {
                        const updated = [...prev];
                        updated[lastToolIdx] = {
                            ...updated[lastToolIdx],
                            permissionId: data.requestId as string,
                            permissionResolved: false,
                            toolInput: (data.toolInput as Record<string, unknown>) || updated[lastToolIdx].toolInput,
                        };
                        return updated;
                    }
                    return [...prev, {
                        id: String(++messageIdCounter),
                        type: "permission_request" as const,
                        permissionId: data.requestId as string,
                        toolName: data.toolName as string,
                        toolInput: data.toolInput as Record<string, unknown>,
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
                break;

            case "permission_resolved":
                setMessages((prev) => {
                    const match = prev.find((m) => m.permissionId === data.permissionId);
                    if (match?.toolName === "ExitPlanMode") {
                        onModeChangeRef.current?.("normal");
                    }
                    return prev.map((m) =>
                        m.permissionId === data.permissionId
                            ? { ...m, permissionResolved: true, permissionAllowed: data.allow as boolean }
                            : m
                    );
                });
                break;

            case "turn_complete":
                setIsProcessing(false);
                if (data.costUsd != null) {
                    onCostUpdateRef.current?.(
                        data.costUsd as number,
                        (data.inputTokens ?? 0) as number,
                        (data.outputTokens ?? 0) as number
                    );
                }
                break;

            case "session_ended":
                sessionEndedRef.current = true;
                setIsProcessing(false);
                break;

            case "conversation_reset":
                setMessages([{
                    id: String(++messageIdCounter),
                    type: "system",
                    content: "Conversation cleared.",
                }]);
                setSubagentCards(new Map());
                setIsProcessing(false);
                break;

            case "tool_progress":
                setMessages((prev) =>
                    prev.map((m) =>
                        m.type === "tool_use" && m.toolUseId === data.toolUseId
                            ? { ...m, elapsedSeconds: data.elapsedSeconds as number }
                            : m
                    )
                );
                break;

            case "allow_all_changed":
                onAllowAllChangedRef.current?.(data.enabled as boolean);
                break;

            case "subagent_started":
                setSubagentCards((prev) => {
                    const next = new Map(prev);
                    next.set(data.toolUseId as string, {
                        id: data.toolUseId as string,
                        taskId: data.taskId as string,
                        description: data.description as string,
                        subagentType: data.subagentType as string,
                        status: "running",
                        toolCount: 0,
                        durationMs: 0,
                        activityLog: [],
                        dismissed: false,
                    });
                    return next;
                });
                break;

            case "subagent_progress":
                setSubagentCards((prev) => {
                    const card = prev.get(data.toolUseId as string);
                    if (!card) return prev;
                    const entry: SubagentActivityEntry = {
                        id: String(++messageIdCounter),
                        toolName: data.lastToolName as string,
                        description: data.description as string,
                    };
                    const next = new Map(prev);
                    next.set(card.id, {
                        ...card,
                        currentActivity: data.description as string,
                        lastToolName: data.lastToolName as string,
                        toolCount: data.toolCount as number,
                        durationMs: data.durationMs as number,
                        activityLog: [...card.activityLog, entry],
                    });
                    return next;
                });
                break;

            case "subagent_status":
                setSubagentCards((prev) => {
                    for (const [id, card] of prev) {
                        if (card.taskId === (data.taskId as string)) {
                            const next = new Map(prev);
                            next.set(id, {
                                ...card,
                                status: (data.status as string) === "completed"
                                    ? "completed" : card.status,
                            });
                            return next;
                        }
                    }
                    return prev;
                });
                break;

            case "subagent_completed":
                setSubagentCards((prev) => {
                    const card = prev.get(data.toolUseId as string);
                    if (!card) return prev;
                    const next = new Map(prev);
                    next.set(card.id, {
                        ...card,
                        status: "completed",
                        summary: data.summary as string,
                    });
                    return next;
                });
                break;

            case "unhandled_event":
                addMessage({
                    type: "warning",
                    content: `Unhandled event type: ${data.rawType}`,
                    rawPayload: data.raw as string,
                });
                break;

            case "session_error":
                addMessage({ type: "system", content: (data.message as string) || "Session error" });
                setIsProcessing(false);
                break;
        }
    }, [addMessage]);

    useEffect(() => {
        sessionEndedRef.current = false;
        lastSeenIndexRef.current = -1;
        setMessages([]);

        sseClient.connect();

        let cancelled = false;
        const buffer: { eventType: string; eventData: Record<string, unknown>; eventIndex: number }[] = [];
        let historyLoaded = false;

        const sessionShort = sessionId.substring(0, 8);
        console.log(`[ChatPanel] Subscribing to session ${sessionShort}`);

        const unsubscribe = sseClient.subscribeSession(sessionId,
            (eventType, eventData, eventIndex) => {
                if (cancelled) {
                    console.warn(`[ChatPanel] DROPPED (cancelled) ${eventType} idx=${eventIndex} for ${sessionShort}`);
                    return;
                }
                if (!historyLoaded) {
                    console.log(`[ChatPanel] Buffering (history loading) ${eventType} idx=${eventIndex} for ${sessionShort}`);
                    buffer.push({ eventType, eventData, eventIndex });
                    return;
                }
                if (eventType === "conversation_reset") {
                    console.log(`[ChatPanel] Processing live ${eventType} idx=${eventIndex} for ${sessionShort} (bypass dedup)`);
                    lastSeenIndexRef.current = eventIndex;
                    processEvent(eventType, eventData);
                    return;
                }
                if (eventIndex <= lastSeenIndexRef.current) {
                    console.log(`[ChatPanel] Skipping (already seen) ${eventType} idx=${eventIndex} <= ${lastSeenIndexRef.current} for ${sessionShort}`);
                    return;
                }
                console.log(`[ChatPanel] Processing live ${eventType} idx=${eventIndex} for ${sessionShort}`);
                lastSeenIndexRef.current = eventIndex;
                processEvent(eventType, eventData);
            }
        );

        function catchUpFromHistory() {
            console.log(`[ChatPanel] Fetching history for ${sessionShort} (lastSeen=${lastSeenIndexRef.current})`);
            fetchAssistantSessionHistory(sessionId)
                .then((history) => {
                    if (cancelled) {
                        console.warn(`[ChatPanel] History response arrived but cancelled for ${sessionShort}`);
                        return;
                    }
                    const newEvents = history.filter(e => e.eventIndex > lastSeenIndexRef.current);
                    console.log(`[ChatPanel] History: ${history.length} total, ${newEvents.length} new (lastSeen=${lastSeenIndexRef.current}) for ${sessionShort}`);
                    for (const event of history) {
                        if (event.eventType === "conversation_reset") {
                            lastSeenIndexRef.current = event.eventIndex;
                            processEvent(event.eventType, event.eventData);
                            continue;
                        }
                        if (event.eventIndex <= lastSeenIndexRef.current) continue;
                        processEvent(event.eventType, event.eventData);
                        lastSeenIndexRef.current = Math.max(lastSeenIndexRef.current, event.eventIndex);
                    }
                    historyLoaded = true;
                    for (const event of buffer) {
                        if (event.eventType === "conversation_reset") {
                            lastSeenIndexRef.current = event.eventIndex;
                            processEvent(event.eventType, event.eventData);
                            continue;
                        }
                        if (event.eventIndex <= lastSeenIndexRef.current) continue;
                        lastSeenIndexRef.current = event.eventIndex;
                        processEvent(event.eventType, event.eventData);
                    }
                    buffer.length = 0;
                })
                .catch((err) => {
                    if (cancelled) return;
                    console.error("Failed to fetch session history:", err);
                    historyLoaded = true;
                    for (const event of buffer) {
                        processEvent(event.eventType, event.eventData);
                    }
                    buffer.length = 0;
                });
        }

        catchUpFromHistory();

        const unsubReconnect = sseClient.onReconnect(() => {
            console.log(`[ChatPanel] SSE reconnected, catching up for ${sessionShort}`);
            if (!cancelled) catchUpFromHistory();
        });

        return () => {
            console.log(`[ChatPanel] Cleaning up subscription for ${sessionShort}`);
            cancelled = true;
            unsubscribe();
            unsubReconnect();
        };
    }, [sessionId, processEvent]);

    const handleSend = useCallback(async (message: string) => {
        if (message.trim() === "/clear") {
            setMessages([{
                id: String(++messageIdCounter),
                type: "system",
                content: "Conversation cleared.",
            }]);
            setIsProcessing(false);
        } else {
            addMessage({ type: "user", content: message });
            setProcessingText(randomThinkingMessage());
            setIsProcessing(true);
        }
        try {
            await sendAssistantMessage(sessionId, message);
        } catch (err) {
            console.error("Failed to send message:", err);
            addMessage({ type: "system", content: "Failed to send message. Please try again." });
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
            addMessage({ type: "system", content: "Failed to submit permission response. Please try again." });
            setIsProcessing(false);
        }
    }, [sessionId, addMessage]);

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
            addMessage({ type: "system", content: "Failed to create auto-approval rule. Please try again." });
        }
    }, [sessionId, onAutoApprovalCountChange, addMessage]);

    const handleDismissSubagent = useCallback((id: string) => {
        setSubagentCards((prev) => {
            const card = prev.get(id);
            if (!card) return prev;
            const next = new Map(prev);
            next.set(id, { ...card, dismissed: true });
            return next;
        });
    }, []);

    const handleDismissAllCompleted = useCallback(() => {
        setSubagentCards((prev) => {
            const next = new Map(prev);
            for (const [id, card] of next) {
                if (card.status === "completed") {
                    next.set(id, { ...card, dismissed: true });
                }
            }
            return next;
        });
    }, []);

    const visibleCards = Array.from(subagentCards.values()).filter(c => !c.dismissed);

    return (
        <div style={{
            display: "flex",
            flex: "1 1 0",
            minHeight: 0,
        }}>
            <div style={{
                display: "flex",
                flexDirection: "column",
                flex: "1 1 0",
                minWidth: 0,
                minHeight: 0,
            }}>
                <AssistantMessageList
                    messages={messages}
                    onPermissionRespond={handlePermissionRespond}
                    onCreateAutoApproval={handleCreateAutoApproval}
                    isProcessing={isProcessing}
                    processingText={processingText}
                />
                <AssistantMessageInput
                    onSend={handleSend}
                    disabled={isProcessing}
                    slashCommands={slashCommands}
                />
            </div>
            {visibleCards.length > 0 && (
                <AssistantSubagentPanel
                    cards={visibleCards}
                    onDismiss={handleDismissSubagent}
                    onDismissAllCompleted={handleDismissAllCompleted}
                />
            )}
        </div>
    );
}
