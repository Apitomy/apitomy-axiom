// Minimal MCP server over stdio for tests: initialize + paginated tools/list.
// Writes a log line to stderr and a non-JSON line to stdout to exercise robustness.
// FAKE_MCP_MODE=crash exits immediately with an error; =hang never answers.
const readline = require("readline");
const mode = process.env.FAKE_MCP_MODE || "";
if (mode === "crash") {
  process.stderr.write("fatal: missing FAKE_TOKEN\n");
  process.exit(3);
}
process.stderr.write("fake server starting\n");
process.stdout.write("not json banner\n");
const rl = readline.createInterface({ input: process.stdin });
const send = (m) => process.stdout.write(JSON.stringify(m) + "\n");
rl.on("line", (line) => {
  if (mode === "hang") return;
  const msg = JSON.parse(line);
  if (msg.method === "initialize") {
    send({ jsonrpc: "2.0", id: 999, method: "ping" });
    send({ jsonrpc: "2.0", id: msg.id, result: {
      protocolVersion: msg.params.protocolVersion, capabilities: { tools: {} },
      serverInfo: { name: "fake-stdio", version: "1.2.3" } } });
  } else if (msg.method === "tools/list") {
    if (!msg.params || !msg.params.cursor) {
      send({ jsonrpc: "2.0", id: msg.id, result: { tools: [
        { name: "echo", description: "Echo " + (process.env.FAKE_TOKEN || "") }], nextCursor: "p2" } });
    } else {
      send({ jsonrpc: "2.0", id: msg.id, result: { tools: [{ name: "add" }] } });
    }
  }
});
