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
