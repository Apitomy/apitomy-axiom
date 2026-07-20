import { getApiBaseUrl } from "./api";

/**
 * SSE event received from the backend.
 */
export interface AxiomSseEvent {
    type: string;
    data: Record<string, unknown>;
}

/**
 * Callback type for global SSE event listeners.
 */
export type SseListener = (event: AxiomSseEvent) => void;

/**
 * Callback type for assistant session event listeners.
 * Receives the assistant event type, its data payload, and a monotonic index.
 */
export type SessionEventListener = (
    eventType: string,
    eventData: Record<string, unknown>,
    eventIndex: number
) => void;

/**
 * Manages a Server-Sent Events connection to the Axiom backend.
 *
 * When the browser supports SharedWorker, a single worker owns the EventSource
 * and broadcasts events to all tabs via MessagePort. This means exactly 1 SSE
 * connection exists regardless of how many tabs/windows are open, avoiding the
 * browser's HTTP/1.1 6-connection-per-origin limit.
 *
 * Falls back to a direct EventSource per tab when SharedWorker is unavailable.
 */
export class SseClient {
    private worker: SharedWorker | null = null;
    private eventSource: EventSource | null = null;
    private listeners: SseListener[] = [];
    private sessionListeners: Map<string, SessionEventListener[]> = new Map();
    private reconnectDelay = 1000;
    private closed = false;
    private connected = false;

    private get useSharedWorker(): boolean {
        return typeof SharedWorker !== "undefined";
    }

    /**
     * Connects to the SSE endpoint. Uses a SharedWorker if available,
     * otherwise falls back to a direct EventSource.
     */
    connect(): void {
        if (this.connected) return;
        this.connected = true;
        this.closed = false;

        if (this.useSharedWorker) {
            this.connectViaWorker();
        } else {
            this.connectDirect();
        }
    }

    private connectViaWorker(): void {
        if (this.worker) return;

        this.worker = new SharedWorker(
            new URL("./sse-worker.ts", import.meta.url),
            { type: "module", name: "sse-shared-worker" }
        );

        this.worker.port.onmessage = (e) => {
            const msg = e.data;
            switch (msg.type) {
                case "event":
                    this.dispatchEvent(msg.event);
                    break;
                case "connected":
                    this.reconnectDelay = 1000;
                    break;
                case "disconnected":
                    break;
            }
        };

        this.worker.port.start();
        this.worker.port.postMessage({ type: "init", baseUrl: getApiBaseUrl() });
        this.worker.port.postMessage({ type: "connect" });
    }

    private connectDirect(): void {
        if (this.eventSource) return;

        const url = `${getApiBaseUrl()}/api/v1/sse`;
        this.eventSource = new EventSource(url);

        this.eventSource.onopen = () => {
            this.reconnectDelay = 1000;
        };

        this.eventSource.onmessage = (event) => {
            try {
                const parsed = JSON.parse(event.data);
                const axiomEvent: AxiomSseEvent = {
                    type: parsed.type,
                    data: typeof parsed.data === "string"
                        ? JSON.parse(parsed.data)
                        : parsed.data ?? {},
                };
                this.dispatchEvent(axiomEvent);
            } catch {
                // ignore
            }
        };

        this.eventSource.onerror = () => {
            if (this.closed) return;
            this.eventSource?.close();
            this.eventSource = null;
            setTimeout(() => {
                if (!this.closed) this.connectDirect();
            }, this.reconnectDelay);
            this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30000);
        };
    }

    private dispatchEvent(event: AxiomSseEvent): void {
        if (event.type === "assistant-session-event") {
            const sessionId = event.data.sessionId as string;
            const eventType = event.data.eventType as string;
            const eventData = event.data.eventData as Record<string, unknown>;
            const eventIndex = event.data.eventIndex as number;
            const sessionCbs = this.sessionListeners.get(sessionId);
            if (sessionCbs) {
                sessionCbs.forEach((cb) => cb(eventType, eventData, eventIndex));
            }
        } else {
            this.listeners.forEach((listener) => listener(event));
        }
    }

    /**
     * Adds a global listener that will be called for every non-session SSE event.
     *
     * @param listener the callback function
     * @returns an unsubscribe function
     */
    subscribe(listener: SseListener): () => void {
        this.listeners.push(listener);
        return () => {
            this.listeners = this.listeners.filter((l) => l !== listener);
        };
    }

    /**
     * Subscribes to events for a specific assistant session. Events are
     * routed by session ID from the multiplexed global SSE channel.
     *
     * @param sessionId the assistant session ID to subscribe to
     * @param listener the callback receiving (eventType, eventData, eventIndex)
     * @returns an unsubscribe function
     */
    subscribeSession(sessionId: string, listener: SessionEventListener): () => void {
        let callbacks = this.sessionListeners.get(sessionId);
        if (!callbacks) {
            callbacks = [];
            this.sessionListeners.set(sessionId, callbacks);
        }
        callbacks.push(listener);

        return () => {
            const cbs = this.sessionListeners.get(sessionId);
            if (cbs) {
                const filtered = cbs.filter((cb) => cb !== listener);
                if (filtered.length === 0) {
                    this.sessionListeners.delete(sessionId);
                } else {
                    this.sessionListeners.set(sessionId, filtered);
                }
            }
        };
    }

    /**
     * Disconnects from the SSE endpoint.
     */
    disconnect(): void {
        this.closed = true;
        this.connected = false;

        if (this.worker) {
            this.worker.port.postMessage({ type: "disconnect" });
            this.worker.port.close();
            this.worker = null;
        }

        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
    }
}

/**
 * Singleton SSE client instance.
 */
export const sseClient = new SseClient();
