const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const server = require(path.resolve(__dirname, "../../main/resources/templates/axiom-run-summary-mcp/server.js"));

const calls = [];
const run = { id: 5, subjectId: 9, status: "failed", traceId: "tr-1",
    history: [{ taskId: 11, nodeId: "fetch", taskStatus: "Failed" }] };
server.setFetch(async (url, opts) => {
    calls.push({ url, method: opts.method });
    const body = url.endsWith("/workflow/runs/5") ? JSON.stringify(run)
        : url.endsWith("/subjects/9/tasks/11") ? JSON.stringify({ id: 11, input: "in", output: "out" })
        : url.endsWith("/subjects/9/tasks/11/log") ? "abcdefghij"
        : url.endsWith("/traces/tr-1") ? JSON.stringify({ nodes: [] }) : null;
    return { ok: body !== null, status: body === null ? 404 : 200, text: async () => body ?? "not found" };
});
const tool = (name) => server.TOOLS.find(t => t.name === name);

test("exposes exactly the four read-only tools", () => {
    assert.deepStrictEqual(server.TOOLS.map(t => t.name).sort(), ["get_run", "get_task", "get_task_log", "get_trace"]);
});

test("get_task returns the task of the run", async () => {
    const out = await tool("get_task").handler({ runId: 5, taskId: 11 });
    assert.match(out, /out/);
});

test("get_task refuses a task that is not part of the run", async () => {
    const out = await tool("get_task").handler({ runId: 5, taskId: 99 });
    assert.match(out, /not part of run 5/);
});

test("get_task_log pages the log", async () => {
    const out = await tool("get_task_log").handler({ runId: 5, taskId: 11, offset: 2, limit: 3 });
    assert.match(out, /cde/);
    assert.match(out, /chars 2-5 of 10/);
});

test("only GET requests are made", async () => {
    await tool("get_run").handler({ runId: 5 });
    await tool("get_trace").handler({ runId: 5 });
    assert.ok(calls.length > 0);
    assert.ok(calls.every(c => c.method === "GET"));
});

test("pageText clamps bounds", () => {
    assert.deepStrictEqual(server.pageText("abc", 5, 10), { text: "", start: 3, end: 3, total: 3 });
});
