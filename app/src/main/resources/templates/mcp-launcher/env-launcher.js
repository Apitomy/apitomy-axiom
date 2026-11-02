// Axiom MCP environment launcher.
//
// Usage: node env-launcher.js --keep NAME1,NAME2,... -- <command> [args...]
//
// Agent CLIs start stdio MCP servers with their own process environment merged with the server's configured
// variables, so a server would otherwise see every variable of the calling agent, including its secrets. This
// launcher starts <command> with only the named variables (the passthrough allowlist plus the server's own
// declared variables) and relays stdio, signals and the exit status. Only variable names are passed on the
// command line, never values.
"use strict";

const { spawn } = require("child_process");
const os = require("os");

function fail(message, code) {
    process.stderr.write("[axiom-env-launcher] " + message + "\n");
    process.exit(code);
}

const argv = process.argv.slice(2);
let keep = [];
let index = 0;
if (argv[index] === "--keep") {
    keep = (argv[index + 1] || "").split(",").filter(name => name.length > 0);
    index += 2;
}
if (argv[index] !== "--" || argv.length <= index + 1) {
    fail("usage: env-launcher.js --keep NAME1,NAME2 -- <command> [args...]", 2);
}
const [command, ...args] = argv.slice(index + 1);

const env = {};
for (const name of keep) {
    if (Object.prototype.hasOwnProperty.call(process.env, name)) {
        env[name] = process.env[name];
    }
}

const child = spawn(command, args, { stdio: "inherit", env });
child.on("error", error => fail("could not start '" + command + "': " + error.message, 127));
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
    process.on(signal, () => child.kill(signal));
}
child.on("exit", (code, signal) => {
    if (signal) {
        process.exit(128 + (os.constants.signals[signal] || 0));
    }
    process.exit(code === null ? 1 : code);
});
