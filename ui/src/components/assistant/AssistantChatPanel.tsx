import { useState, useEffect, useCallback, useRef, useMemo } from "react";
import { AssistantMessageList, type ChatMessage } from "./AssistantMessageList";
import { AssistantMessageInput } from "./AssistantMessageInput";
import { AssistantSubagentPanel } from "./AssistantSubagentPanel";
import { AssistantTodoPanel, type AssistantTodo } from "./AssistantTodoPanel";
import type { SubagentCardData, SubagentActivityEntry, SubagentPermission } from "./AssistantSubagentCard";
import type { BackgroundTaskCardData } from "./AssistantBackgroundTaskCard";
import {
    sendAssistantMessage,
    respondToAssistantPermission,
    createAutoApproval,
    dismissAssistantCard,
    fetchAssistantSessionHistory,
    setSubagentAllowAll,
} from "../../config/api";
import { sseClient } from "../../config/sse";
import { randomThinkingMessage } from "./thinkingMessages";

/** Session error names that are informational notices and do not end the current turn. */
const NON_TERMINAL_SESSION_ERRORS = new Set([
    "McpServerUnavailable",
    "ModelUnavailable",
    "MessageNotDelivered",
    "InterruptFailed",
    "EventStreamReconnected",
]);

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
    const [todos, setTodos] = useState<AssistantTodo[]>([]);
    const [isProcessing, setIsProcessingState] = useState(false);
    // Synchronous mirror of isProcessing, readable inside event handlers.
    const processingRef = useRef(false);
    // Whether a turn was already running before the most recent user message was submitted.
    const processingBeforeLastUserMessageRef = useRef(false);
    // Content of a locally-sent message awaiting its user_message echo.
    const pendingLocalUserMessageRef = useRef<string | null>(null);
    const setIsProcessing = useCallback((value: boolean) => {
        processingRef.current = value;
        setIsProcessingState(value);
    }, []);
    const [processingText, setProcessingText] = useState("");
    const [slashCommands, setSlashCommands] = useState<string[]>([]);
    const [subagentCards, setSubagentCards] = useState<Map<string, SubagentCardData>>(new Map());
    const [backgroundTaskCards, setBackgroundTaskCards] = useState<Map<string, BackgroundTaskCardData>>(new Map());
    const [panelWidth, setPanelWidth] = useState(320);
    const sessionEndedRef = useRef(false);
    const lastSeenIndexRef = useRef(-1);
    const pendingSubagentPermissionsRef = useRef<Map<string, SubagentPermission[]>>(new Map());

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
                    if (pendingLocalUserMessageRef.current === data.content) {
                        // Echo of a local send: the pre-send state was already captured in sendMessage.
                        pendingLocalUserMessageRef.current = null;
                    } else {
                        processingBeforeLastUserMessageRef.current = processingRef.current;
                    }
                    setIsProcessing(true);
                }
                break;

            case "thinking": {
                setProcessingText(randomThinkingMessage());
                const thinkingText: unknown = data.text;
                const thinkingId: string | undefined = typeof data.id === "string" ? data.id : undefined;
                if (typeof thinkingText === "string" && thinkingText.length > 0) {
                    setMessages(prev => {
                        if (thinkingId !== undefined
                                && prev.some(m => m.type === "thinking" && m.thinkingId === thinkingId)) {
                            return prev.map(m => m.type === "thinking" && m.thinkingId === thinkingId
                                ? { ...m, content: thinkingText } : m);
                        }
                        return [...prev, {
                            id: String(++messageIdCounter),
                            type: "thinking",
                            content: thinkingText,
                            thinkingId,
                        }];
                    });
                }
                break;
            }

            case "todos":
                setTodos(Array.isArray(data.todos) ? data.todos as AssistantTodo[] : []);
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
                            ? { ...m, toolResult: (data.stdout || data.stderr || "") as string, isError: typeof data.isError === "boolean" ? data.isError : (!!data.stderr && !data.stdout) }
                            : m
                    )
                );
                onItemsChangedRef.current?.();
                break;

            case "permission_request": {
                const newPerm: SubagentPermission = {
                    permissionId: data.requestId as string,
                    toolName: data.toolName as string,
                    toolInput: data.toolInput as Record<string, unknown>,
                    resolved: false,
                };
                const subagentKey = data.subagentToolUseId as string | undefined;
                const agentId = data.agentId as string | undefined;
                if (subagentKey || agentId) {
                    setSubagentCards((prev) => {
                        let card: SubagentCardData | undefined;
                        if (subagentKey) {
                            card = prev.get(subagentKey);
                        }
                        if (!card && agentId) {
                            for (const c of prev.values()) {
                                if (c.taskId === agentId) {
                                    card = c;
                                    break;
                                }
                            }
                        }
                        if (!card) {
                            const buf = pendingSubagentPermissionsRef.current;
                            const key = subagentKey || agentId!;
                            buf.set(key, [...(buf.get(key) || []), newPerm]);
                            return prev;
                        }
                        const next = new Map(prev);
                        next.set(card.id, {
                            ...card,
                            permissions: [...card.permissions, newPerm],
                        });
                        return next;
                    });
                } else {
                    setMessages((prev) => {
                        const toolUseId = data.toolUseId;
                        if (typeof toolUseId === "string" && toolUseId !== "") {
                            const idIdx = prev.findLastIndex(
                                (m) => m.type === "tool_use" && m.toolUseId === toolUseId
                            );
                            if (idIdx >= 0) {
                                const target = prev[idIdx];
                                const updated = [...prev];
                                updated[idIdx] = {
                                    ...target,
                                    permissionId: data.requestId as string,
                                    permissionResolved: false,
                                    permissionAllowed: undefined,
                                    permissionType: data.permission as string | undefined,
                                    permissionPatterns: data.patterns as string[] | undefined,
                                    toolInput: data.toolName === target.toolName && data.toolInput
                                        ? data.toolInput as Record<string, unknown>
                                        : target.toolInput,
                                };
                                return updated;
                            }
                        }
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
                }
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
            }

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
                setSubagentCards((prev) => {
                    for (const [id, card] of prev) {
                        const idx = card.permissions.findIndex(
                            (p) => p.permissionId === (data.permissionId as string)
                        );
                        if (idx >= 0) {
                            const next = new Map(prev);
                            const updatedPerms = [...card.permissions];
                            updatedPerms[idx] = {
                                ...updatedPerms[idx],
                                resolved: true,
                                allowed: data.allow as boolean,
                            };
                            next.set(id, { ...card, permissions: updatedPerms });
                            return next;
                        }
                    }
                    return prev;
                });
                break;

            case "turn_complete":
                setIsProcessing(false);
                if (data.sessionCostUsd != null || data.costUsd != null) {
                    onCostUpdateRef.current?.(
                        (data.sessionCostUsd ?? data.costUsd) as number,
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
                setTodos([]);
                setSubagentCards(new Map());
                setBackgroundTaskCards(new Map());
                pendingSubagentPermissionsRef.current.clear();
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

            case "subagent_allow_all_changed":
                setSubagentCards((prev) => {
                    const card = prev.get(data.subagentToolUseId as string);
                    if (!card) return prev;
                    const next = new Map(prev);
                    next.set(card.id, { ...card, allowAll: data.enabled as boolean });
                    return next;
                });
                break;

            case "subagent_started":
                setSubagentCards((prev) => {
                    const toolUseId = data.toolUseId as string;
                    const taskId = data.taskId as string;
                    const buf = pendingSubagentPermissionsRef.current;
                    const buffered = [...(buf.get(toolUseId) || []), ...(buf.get(taskId) || [])];
                    buf.delete(toolUseId);
                    buf.delete(taskId);
                    const next = new Map(prev);
                    next.set(toolUseId, {
                        id: toolUseId,
                        taskId: data.taskId as string,
                        description: data.description as string,
                        subagentType: data.subagentType as string,
                        status: "running",
                        toolCount: 0,
                        durationMs: 0,
                        activityLog: [],
                        permissions: buffered,
                        dismissed: false,
                        allowAll: false,
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
                setBackgroundTaskCards((prev) => {
                    const card = prev.get(data.toolUseId as string);
                    if (!card) return prev;
                    const next = new Map(prev);
                    next.set(card.id, {
                        ...card,
                        durationMs: data.durationMs as number,
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
                setBackgroundTaskCards((prev) => {
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
                setBackgroundTaskCards((prev) => {
                    const card = prev.get(data.toolUseId as string);
                    if (!card) return prev;
                    const next = new Map(prev);
                    next.set(card.id, { ...card, status: "completed" });
                    return next;
                });
                break;

            case "background_task_started":
                setBackgroundTaskCards((prev) => {
                    const next = new Map(prev);
                    next.set(data.toolUseId as string, {
                        id: data.toolUseId as string,
                        taskId: data.taskId as string,
                        description: data.description as string,
                        status: "running",
                        durationMs: 0,
                        dismissed: false,
                    });
                    return next;
                });
                break;

            case "card_dismissed":
                setSubagentCards((prev) => {
                    const card = prev.get(data.cardId as string);
                    if (!card || card.dismissed) return prev;
                    const next = new Map(prev);
                    next.set(card.id, { ...card, dismissed: true });
                    return next;
                });
                setBackgroundTaskCards((prev) => {
                    const card = prev.get(data.cardId as string);
                    if (!card || card.dismissed) return prev;
                    const next = new Map(prev);
                    next.set(card.id, { ...card, dismissed: true });
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
                if (data.name === "MessageNotDelivered") {
                    // The message never reached the engine: restore the state from before it was submitted.
                    setIsProcessing(processingBeforeLastUserMessageRef.current);
                } else if (!NON_TERMINAL_SESSION_ERRORS.has(data.name as string)) {
                    setIsProcessing(false);
                }
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
            setSubagentCards(new Map());
            setBackgroundTaskCards(new Map());
            setIsProcessing(false);
        } else {
            processingBeforeLastUserMessageRef.current = processingRef.current;
            pendingLocalUserMessageRef.current = message;
            addMessage({ type: "user", content: message });
            setProcessingText(randomThinkingMessage());
            setIsProcessing(true);
        }
        try {
            await sendAssistantMessage(sessionId, message);
        } catch (err) {
            console.error("Failed to send message:", err);
            addMessage({ type: "system", content: "Failed to send message. Please try again." });
            pendingLocalUserMessageRef.current = null;
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
        setSubagentCards((prev) => {
            for (const [id, card] of prev) {
                const idx = card.permissions.findIndex((p) => p.permissionId === permissionId);
                if (idx >= 0) {
                    const next = new Map(prev);
                    const updatedPerms = [...card.permissions];
                    updatedPerms[idx] = { ...updatedPerms[idx], resolved: true, allowed: allow };
                    next.set(id, { ...card, permissions: updatedPerms });
                    return next;
                }
            }
            return prev;
        });
        setIsProcessing(true);
        try {
            await respondToAssistantPermission(sessionId, permissionId, allow, toolInput);
        } catch (err) {
            console.error("Failed to respond to permission:", err);
            addMessage({ type: "system", content: "Failed to submit permission response. Please try again." });
            setIsProcessing(false);
        }
    }, [sessionId, addMessage]);

    const handleSubagentAllowAll = useCallback(async (subagentToolUseId: string) => {
        const card = subagentCards.get(subagentToolUseId);
        const unresolvedPerms = card ? card.permissions.filter((p) => !p.resolved) : [];

        setSubagentCards((prev) => {
            const c = prev.get(subagentToolUseId);
            if (!c) return prev;
            const next = new Map(prev);
            const updatedPerms = c.permissions.map((p) =>
                p.resolved ? p : { ...p, resolved: true, allowed: true }
            );
            next.set(c.id, { ...c, allowAll: true, permissions: updatedPerms });
            return next;
        });

        try {
            await setSubagentAllowAll(sessionId, subagentToolUseId, true);
        } catch (err) {
            console.error("Failed to enable subagent Allow All:", err);
        }

        for (const perm of unresolvedPerms) {
            try {
                await respondToAssistantPermission(sessionId, perm.permissionId, true, perm.toolInput);
            } catch (err) {
                console.error("Failed to approve permission:", perm.permissionId, err);
            }
        }
        if (unresolvedPerms.length > 0) {
            setIsProcessing(true);
        }
    }, [sessionId, subagentCards]);

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

    const handleDismissCard = useCallback((id: string) => {
        dismissAssistantCard(sessionId, id).catch((err) => {
            console.error("Failed to dismiss card:", err);
        });
    }, [sessionId]);

    const handleDismissAllCompleted = useCallback(() => {
        setSubagentCards((prev) => {
            const next = new Map(prev);
            let changed = false;
            for (const [id, card] of next) {
                if (card.status === "completed" && !card.dismissed) {
                    next.set(id, { ...card, dismissed: true });
                    changed = true;
                }
            }
            return changed ? next : prev;
        });
        setBackgroundTaskCards((prev) => {
            const next = new Map(prev);
            let changed = false;
            for (const [id, card] of next) {
                if (card.status === "completed" && !card.dismissed) {
                    next.set(id, { ...card, dismissed: true });
                    changed = true;
                }
            }
            return changed ? next : prev;
        });

        for (const card of subagentCards.values()) {
            if (card.status === "completed" && !card.dismissed) {
                dismissAssistantCard(sessionId, card.id).catch((err) => {
                    console.error("Failed to dismiss card:", err);
                });
            }
        }
        for (const card of backgroundTaskCards.values()) {
            if (card.status === "completed" && !card.dismissed) {
                dismissAssistantCard(sessionId, card.id).catch((err) => {
                    console.error("Failed to dismiss card:", err);
                });
            }
        }
    }, [sessionId, subagentCards, backgroundTaskCards]);

    const [highlightedCardId, setHighlightedCardId] = useState<string | undefined>(undefined);
    const [highlightedAgentBlockId, setHighlightedAgentBlockId] = useState<string | undefined>(undefined);

    const handleSubagentClick = useCallback((toolUseId: string) => {
        setHighlightedCardId(toolUseId);
        setTimeout(() => setHighlightedCardId(undefined), 2000);
    }, []);

    const handleNavigateToAgent = useCallback((toolUseId: string) => {
        const el = document.querySelector(`[data-tool-use-id="${CSS.escape(toolUseId)}"]`);
        if (el) {
            el.scrollIntoView({ behavior: "smooth", block: "center" });
        }
        setHighlightedAgentBlockId(toolUseId);
        setTimeout(() => setHighlightedAgentBlockId(undefined), 2000);
    }, []);

    const visibleSubagentCards = useMemo(
        () => Array.from(subagentCards.values()).filter(c => !c.dismissed),
        [subagentCards]
    );
    const visibleBgTaskCards = useMemo(
        () => Array.from(backgroundTaskCards.values()).filter(c => !c.dismissed),
        [backgroundTaskCards]
    );
    const hasSidePanel = visibleSubagentCards.length > 0 || visibleBgTaskCards.length > 0;

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
                <AssistantTodoPanel todos={todos} />
                <AssistantMessageList
                    messages={messages}
                    onPermissionRespond={handlePermissionRespond}
                    onCreateAutoApproval={handleCreateAutoApproval}
                    isProcessing={isProcessing}
                    processingText={processingText}
                    onSubagentClick={handleSubagentClick}
                    highlightedAgentBlockId={highlightedAgentBlockId}
                />
                <AssistantMessageInput
                    onSend={handleSend}
                    disabled={isProcessing}
                    slashCommands={slashCommands}
                />
            </div>
            {hasSidePanel && (
                <AssistantSubagentPanel
                    subagentCards={visibleSubagentCards}
                    backgroundTaskCards={visibleBgTaskCards}
                    onDismissSubagent={handleDismissCard}
                    onDismissBackgroundTask={handleDismissCard}
                    onDismissAllCompleted={handleDismissAllCompleted}
                    onNavigateToAgent={handleNavigateToAgent}
                    onPermissionRespond={handlePermissionRespond}
                    onAllowAll={handleSubagentAllowAll}
                    highlightedCardId={highlightedCardId}
                    width={panelWidth}
                    onWidthChange={setPanelWidth}
                />
            )}
        </div>
    );
}
