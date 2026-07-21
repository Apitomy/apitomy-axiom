const _self = self as unknown as SharedWorkerGlobalScope;

const PREFIX = "[SSE Worker]";

const ports = new Set<MessagePort>();
const alivePorts = new Set<MessagePort>();
let eventSource: EventSource | null = null;
let baseUrl = "";
let reconnectDelay = 1000;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let stalenessTimer: ReturnType<typeof setInterval> | null = null;
let portCleanupTimer: ReturnType<typeof setInterval> | null = null;
let closed = false;
let eventCount = 0;
let lastEventTime = 0;

const STALENESS_CHECK_INTERVAL = 10_000;
const STALENESS_THRESHOLD = 30_000;
const PORT_CLEANUP_INTERVAL = 30_000;

function broadcast(message: unknown) {
    for (const port of ports) {
        try {
            port.postMessage(message);
        } catch {
            console.warn(`${PREFIX} Failed to post to port, removing`);
            ports.delete(port);
            alivePorts.delete(port);
        }
    }
}

function startPortCleanup() {
    if (portCleanupTimer) return;
    portCleanupTimer = setInterval(() => {
        const stale = ports.size - alivePorts.size;
        if (stale > 0) {
            console.log(`${PREFIX} Removing ${stale} stale port(s) (${alivePorts.size} alive, ${ports.size} total)`);
            for (const port of ports) {
                if (!alivePorts.has(port)) {
                    ports.delete(port);
                }
            }
            if (ports.size === 0 && eventSource) {
                disconnectSse();
            }
        }
        alivePorts.clear();
        for (const port of ports) {
            try {
                port.postMessage({ type: "ping" });
            } catch {
                ports.delete(port);
            }
        }
    }, PORT_CLEANUP_INTERVAL);
}

function startStalenessCheck() {
    if (stalenessTimer) return;
    stalenessTimer = setInterval(() => {
        if (!eventSource || closed || ports.size === 0) return;
        const elapsed = Date.now() - lastEventTime;
        if (elapsed > STALENESS_THRESHOLD && eventSource.readyState !== EventSource.CLOSED) {
            console.warn(`${PREFIX} No data received for ${Math.round(elapsed / 1000)}s, forcing reconnect`);
            eventSource.close();
            eventSource = null;
            broadcast({ type: "disconnected" });
            connectSse();
        }
    }, STALENESS_CHECK_INTERVAL);
}

function stopStalenessCheck() {
    if (stalenessTimer) {
        clearInterval(stalenessTimer);
        stalenessTimer = null;
    }
}

function connectSse() {
    if (eventSource) {
        console.log(`${PREFIX} EventSource already exists, skipping connect`);
        return;
    }

    closed = false;
    eventCount = 0;
    lastEventTime = Date.now();
    const url = `${baseUrl}/api/v1/sse`;
    console.log(`${PREFIX} Connecting to ${url} (${ports.size} tab(s) connected)`);
    eventSource = new EventSource(url);

    eventSource.onopen = () => {
        reconnectDelay = 1000;
        lastEventTime = Date.now();
        console.log(`${PREFIX} EventSource connected`);
        broadcast({ type: "connected" });
        startStalenessCheck();
    };

    eventSource.onmessage = (e) => {
        lastEventTime = Date.now();
        try {
            const parsed = JSON.parse(e.data);
            const event = {
                type: parsed.type as string,
                data: typeof parsed.data === "string"
                    ? JSON.parse(parsed.data)
                    : parsed.data ?? {},
            };
            if (event.type === "heartbeat") return;
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
        stopStalenessCheck();
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
    stopStalenessCheck();
    eventSource?.close();
    eventSource = null;
}

_self.onconnect = (e: MessageEvent) => {
    const port = e.ports[0];
    ports.add(port);
    alivePorts.add(port);
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
                alivePorts.delete(port);
                console.log(`${PREFIX} Tab disconnected (${ports.size} remaining)`);
                if (ports.size === 0) {
                    disconnectSse();
                }
                break;
            case "pong":
                alivePorts.add(port);
                break;
            default:
                console.warn(`${PREFIX} Unknown message type: ${data.type}`);
        }
    };

    port.start();
    startPortCleanup();
};

console.log(`${PREFIX} SharedWorker started`);
