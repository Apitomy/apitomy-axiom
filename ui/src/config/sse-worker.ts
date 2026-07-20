const _self = self as unknown as SharedWorkerGlobalScope;

const PREFIX = "[SSE Worker]";

const ports = new Set<MessagePort>();
let eventSource: EventSource | null = null;
let baseUrl = "";
let reconnectDelay = 1000;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let closed = false;
let eventCount = 0;

function broadcast(message: unknown) {
    for (const port of ports) {
        try {
            port.postMessage(message);
        } catch {
            console.warn(`${PREFIX} Failed to post to port, removing`);
            ports.delete(port);
        }
    }
}

function connectSse() {
    if (eventSource) {
        console.log(`${PREFIX} EventSource already exists, skipping connect`);
        return;
    }

    closed = false;
    eventCount = 0;
    const url = `${baseUrl}/api/v1/sse`;
    console.log(`${PREFIX} Connecting to ${url} (${ports.size} tab(s) connected)`);
    eventSource = new EventSource(url);

    eventSource.onopen = () => {
        reconnectDelay = 1000;
        console.log(`${PREFIX} EventSource connected`);
        broadcast({ type: "connected" });
    };

    eventSource.onmessage = (e) => {
        try {
            const parsed = JSON.parse(e.data);
            const event = {
                type: parsed.type as string,
                data: typeof parsed.data === "string"
                    ? JSON.parse(parsed.data)
                    : parsed.data ?? {},
            };
            eventCount++;
            if (eventCount <= 5 || eventCount % 100 === 0) {
                console.log(`${PREFIX} Event #${eventCount}: ${event.type}` +
                    (event.type === "assistant-session-event"
                        ? ` (session=${event.data.sessionId}, eventType=${event.data.eventType})`
                        : "")
                    + ` → broadcasting to ${ports.size} tab(s)`);
            }
            broadcast({ type: "event", event });
        } catch {
            console.warn(`${PREFIX} Failed to parse SSE event:`, e.data);
        }
    };

    eventSource.onerror = () => {
        if (closed) {
            console.log(`${PREFIX} EventSource error after intentional close, ignoring`);
            return;
        }
        console.warn(`${PREFIX} EventSource error, closing connection`);
        eventSource?.close();
        eventSource = null;
        broadcast({ type: "disconnected" });

        if (ports.size > 0) {
            console.log(`${PREFIX} Scheduling reconnect in ${reconnectDelay}ms`
                + ` (${ports.size} tab(s) still connected)`);
            reconnectTimer = setTimeout(() => {
                reconnectTimer = null;
                if (!closed && ports.size > 0) connectSse();
            }, reconnectDelay);
            reconnectDelay = Math.min(reconnectDelay * 2, 30000);
        } else {
            console.log(`${PREFIX} No tabs connected, skipping reconnect`);
        }
    };
}

function disconnectSse() {
    console.log(`${PREFIX} Disconnecting (no tabs remaining)`);
    closed = true;
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    eventSource?.close();
    eventSource = null;
}

_self.onconnect = (e: MessageEvent) => {
    const port = e.ports[0];
    ports.add(port);
    console.log(`${PREFIX} Tab connected (${ports.size} total)`);

    port.onmessage = (msg) => {
        const data = msg.data;
        switch (data.type) {
            case "init":
                if (data.baseUrl !== undefined) {
                    baseUrl = data.baseUrl;
                    console.log(`${PREFIX} Initialized with baseUrl="${baseUrl}"`);
                }
                break;
            case "connect":
                console.log(`${PREFIX} Tab requested SSE connect`);
                connectSse();
                break;
            case "disconnect":
                ports.delete(port);
                console.log(`${PREFIX} Tab disconnected (${ports.size} remaining)`);
                if (ports.size === 0) {
                    disconnectSse();
                }
                break;
            default:
                console.warn(`${PREFIX} Unknown message type: ${data.type}`);
        }
    };

    port.start();
};

console.log(`${PREFIX} SharedWorker started`);
