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

test("formatTestRun summarizes status, outputs, problems and output tails", () => {
    const text = helpers.formatTestRun({
        itemType: "action-type", itemName: "greet", status: "failed", exitCode: 0, durationMs: 12,
        outputs: { greeting: "hi" }, problems: ["Output: Missing required field 'count'."],
        stdout: "x".repeat(5000), stderr: "warn",
    });
    assert.match(text, /^FAILED: action-type greet \(exit 0, 12 ms\)/);
    assert.match(text, /Outputs: \{"greeting":"hi"\}/);
    assert.match(text, /- Output: Missing required field 'count'\./);
    assert.match(text, /stdout \(last 4000 chars\):\n…x{4000}/);
    assert.match(text, /stderr:\nwarn/);
});

test("formatTestRun explains a run that could not start", () => {
    assert.strictEqual(helpers.formatTestRun({ itemType: "action-type", itemName: "a", status: "error",
        message: "Test runs of AI Agent action types are not supported yet.", problems: [], stdout: "", stderr: "" }),
        "ERROR: action-type a\nTest runs of AI Agent action types are not supported yet.");
});

test("testRunBody builds the agent test request", () => {
    assert.deepStrictEqual(helpers.testRunBody("tool", "t", { a: 1 }, undefined),
        { type: "tool", name: "t", inputs: { a: 1 } });
    assert.deepStrictEqual(helpers.testRunBody("action-type", "s", {}, "7"),
        { type: "action-type", name: "s", inputs: {}, subjectId: 7 });
});

test("testRunBody rejects a non-integer subjectId", () => {
    for (const bad of [1.5, "abc", "", true]) {
        assert.throws(() => helpers.testRunBody("tool", "t", {}, bad), /subjectId must be an integer/);
    }
});
