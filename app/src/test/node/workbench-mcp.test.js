const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const helpers = require(path.resolve(__dirname, "../../main/resources/templates/axiom-workbench-mcp/server.js"));

test("summarizeEvent lists id, type, ref, actor and payload keys", () => {
    const line = helpers.summarizeEvent({ id: "e1", type: "issue.comment.created", eventSourceId: "gh",
        ref: "r", timestamp: "t", actor: { login: "octo" }, payload: { comment: {}, issue: {} } });
    assert.match(line, /^e1 issue\.comment\.created gh r actor=octo at t keys=\[comment, issue\]$/);
});

test("pickPath handles dots and indexes", () => {
    const obj = { payload: { issue: { labels: [{ name: "bug" }] } } };
    assert.strictEqual(helpers.pickPath(obj, "payload.issue.labels[0].name"), "bug");
    assert.strictEqual(helpers.pickPath(obj, "payload.nope.x"), undefined);
});

test("truncateDeep limits long strings and arrays", () => {
    const out = helpers.truncateDeep({ s: "x".repeat(10), a: [1, 2, 3, 4] }, 4, 2);
    assert.strictEqual(out.s, "xxxx…[truncated 6 chars]");
    assert.deepStrictEqual(out.a, [1, 2, "…[2 more items]"]);
});

test("formatProblems renders one line per problem", () => {
    assert.strictEqual(helpers.formatProblems([{ severity: "error", path: "content", code: "c", message: "m" }]),
        "- [ERROR] content c: m");
});

test("suggestionBody builds the suggestion request", () => {
    assert.deepStrictEqual(helpers.suggestionBody({ type: "tool", name: "t", inputs: { a: 1 } }),
        { type: "tool", name: "t", inputs: { a: 1 } });
    assert.deepStrictEqual(helpers.suggestionBody({ type: "action-type", name: "s", inputs: "{\"x\":2}" }),
        { type: "action-type", name: "s", inputs: { x: 2 } });
    assert.deepStrictEqual(helpers.suggestionBody({ type: "tool", name: "t" }),
        { type: "tool", name: "t", inputs: {} });
});

test("suggestionBody rejects an unknown type", () => {
    for (const bad of [undefined, "workflow", "Tool"]) {
        assert.throws(() => helpers.suggestionBody({ type: bad, name: "t" }), /type must be action-type or tool/);
    }
});

test("formatRunList renders one line per run: subject, optional title, current node", () => {
    const text = helpers.formatRunList([
        { id: 7, status: "failed", definitionVersion: 3, subjectName: "PR 12", title: "Fix bug",
            startedOn: "2026-10-14T10:00:00Z", currentNodeName: "Build" },
        { id: 6, status: "completed", definitionVersion: 2, subjectName: "PR 11", startedOn: "2026-10-13T10:00:00Z" },
    ]);
    assert.strictEqual(text, "#7 failed v3 PR 12 \"Fix bug\" started 2026-10-14T10:00:00Z at Build\n"
        + "#6 completed v2 PR 11 started 2026-10-13T10:00:00Z");
    assert.strictEqual(helpers.formatRunList([]), "No runs found.");
});

test("formatRun renders header, history and failed task details", () => {
    const text = helpers.formatRun({
        id: 7, status: "failed", definitionVersion: 3, title: "Fix bug", currentNodeId: "n2",
        currentNodeName: "Build", failureReason: "boom", traceId: "tr-1",
        history: [
            { nodeId: "n1", nodeName: "Start", taskStatus: "completed", input: { a: 1 }, output: { b: 2 } },
            { nodeId: "n2", nodeName: "Build", taskId: 11, taskStatus: "failed", input: {} },
        ],
    }, [{ taskId: 11, nodeName: "Build", output: { error: "x" }, log: "last lines" }]);
    assert.match(text, /^Run #7: Fix bug\nstatus: failed\nversion: 3\ncurrent node: Build \(n2\)\nfailure reason: boom\ntraceId: tr-1/);
    assert.match(text, /- Start \(n1\) completed input=\{"a":1\} output=\{"b":2\}/);
    assert.match(text, /- Build \(n2\) failed input=\{\}/);
    assert.match(text, /Failed task 11 \(Build\)\noutput: \{"error":"x"\}\nlog \(tail\):\nlast lines/);
});

test("formatRun says when a failed task has no log", () => {
    const text = helpers.formatRun({ id: 1, status: "failed", history: [] },
        [{ taskId: 2, nodeName: "N", output: null, log: null }]);
    assert.match(text, /log: none/);
});

test("formatRun omits the title when the run has none and names the subject", () => {
    const text = helpers.formatRun({ id: 3, status: "running", subjectName: "PR 9", history: [] }, []);
    assert.match(text, /^Run #3\nsubject: PR 9\nstatus: running/);
});

test("formatTrace lists failed nodes first", () => {
    const text = helpers.formatTrace({
        trace: { traceId: "tr-1", status: "failed", summary: "s" },
        nodes: [
            { nodeType: "action", status: "completed", summary: "ok", durationMs: 5 },
            { nodeType: "tool", status: "failed", summary: "bad", durationMs: 9 },
        ],
    });
    const lines = text.split("\n");
    assert.strictEqual(lines[0], "Trace tr-1: failed - s");
    assert.strictEqual(lines[1], "- tool failed bad (9 ms)");
    assert.strictEqual(lines[2], "- action completed ok (5 ms)");
});

test("formatRunList uses the runId of run summaries", () => {
    assert.strictEqual(helpers.formatRunList([{ runId: 42, status: "running", definitionVersion: 1,
        subjectName: "S", startedOn: "s" }]), "#42 running v1 S started s");
});

test("formatSubscriptionPreview summarises matches and errors", () => {
    const text = helpers.formatSubscriptionPreview({
        results: [
            { matched: true, event: { id: "e1", type: "pr.created", ref: "https://x/pull/1", actor: { login: "renovate[bot]" },
                timestamp: "2026-10-15T10:00:00Z" } },
            { matched: false, event: { id: "e2", type: "issue.created", ref: "https://x/issues/2", timestamp: "t" } },
        ],
        totalMatched: 7, errorCount: 1, firstError: "boom",
    });
    assert.strictEqual(text, "1 of 2 recent events match (7 in all events).\n"
        + "1 event(s) could not be evaluated; first error: boom\n"
        + "- e1 pr.created https://x/pull/1 by renovate[bot] at 2026-10-15T10:00:00Z");
});

test("formatSubscriptionPreview with no matches", () => {
    assert.strictEqual(helpers.formatSubscriptionPreview({ results: [], totalMatched: 0, errorCount: 0 }),
        "0 of 0 recent events match (0 in all events).");
});

test("validateArgs accepts calls matching the declared parameters", () => {
    const upsert = helpers.TOOLS.find(t => t.name === "upsert_item");
    assert.strictEqual(helpers.validateArgs(upsert, { type: "tool", item: {} }), null);
    const validate = helpers.TOOLS.find(t => t.name === "validate_composition");
    assert.strictEqual(helpers.validateArgs(validate, {}), null);
});

test("validateArgs reports a misnamed upsert_item argument in the tool's own terms", () => {
    const upsert = helpers.TOOLS.find(t => t.name === "upsert_item");
    assert.strictEqual(helpers.validateArgs(upsert, { type: "action-type", content: "{}" }),
        "Invalid arguments for upsert_item: missing required argument 'item'; unknown argument 'content'. "
        + "Expected: type: string, item: object.");
});

test("validateArgs reports misnamed suggest_test arguments and optional parameters", () => {
    const suggest = helpers.TOOLS.find(t => t.name === "suggest_test");
    assert.strictEqual(helpers.validateArgs(suggest, { targetType: "tool", targetName: "t", inputs: {} }),
        "Invalid arguments for suggest_test: missing required arguments 'type', 'name'; "
        + "unknown arguments 'targetType', 'targetName'. Expected: type: string, name: string, inputs?: object.");
});

test("validateArgs treats null as missing and rejects arguments to a no-argument tool", () => {
    const upsert = helpers.TOOLS.find(t => t.name === "upsert_item");
    assert.match(helpers.validateArgs(upsert, { type: "tool", item: null }), /missing required argument 'item'/);
    const validate = helpers.TOOLS.find(t => t.name === "validate_composition");
    assert.strictEqual(helpers.validateArgs(validate, { x: 1 }),
        "Invalid arguments for validate_composition: unknown argument 'x'. Expected: (no arguments).");
});
