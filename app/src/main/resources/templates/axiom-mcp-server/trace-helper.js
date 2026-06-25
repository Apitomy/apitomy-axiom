const TRACE_ID = process.env.AXIOM_TRACE_ID;
const PARENT_NODE_ID = process.env.AXIOM_PARENT_NODE_ID;
const AXIOM_API_URL = process.env.AXIOM_API_URL;

const MAX_OUTPUT_LENGTH = 100000;

const tracingEnabled = !!(TRACE_ID && PARENT_NODE_ID && AXIOM_API_URL);

async function startToolTrace(toolName, toolInput) {
    if (!tracingEnabled) return null;
    try {
        const resp = await fetch(`${AXIOM_API_URL}/traces/tool-calls`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                traceId: TRACE_ID,
                parentNodeId: parseInt(PARENT_NODE_ID),
                toolName: toolName,
                toolInput: typeof toolInput === "string"
                    ? toolInput.substring(0, MAX_OUTPUT_LENGTH)
                    : JSON.stringify(toolInput).substring(0, MAX_OUTPUT_LENGTH)
            })
        });
        if (resp.ok) {
            const data = await resp.json();
            return data.nodeId;
        }
    } catch (e) {
        console.error("Trace start failed:", e.message);
    }
    return null;
}

async function completeToolTrace(nodeId, toolOutput, status, durationMs) {
    if (!nodeId) return;
    try {
        const truncatedOutput = typeof toolOutput === "string"
            ? toolOutput.substring(0, MAX_OUTPUT_LENGTH)
            : JSON.stringify(toolOutput).substring(0, MAX_OUTPUT_LENGTH);
        await fetch(`${AXIOM_API_URL}/traces/tool-calls/${nodeId}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                toolOutput: truncatedOutput,
                status: status,
                durationMs: durationMs
            })
        });
    } catch (e) {
        console.error("Trace complete failed:", e.message);
    }
}

module.exports = { startToolTrace, completeToolTrace, tracingEnabled };
