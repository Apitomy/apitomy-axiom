// Runs a node-mode Axiom action: node axiom-node-runner.mjs <action.mjs>
// Reads the declared inputs as one JSON object on stdin, calls run(inputs, ctx), and prints the result
// as the final line on stdout. Diagnostics go to stderr.
import { spawn } from "node:child_process";
import { pathToFileURL } from "node:url";
import { format } from "node:util";

function fail(line, error) {
    process.stderr.write(line + "\n");
    if (error && error.stack) {
        process.stderr.write(error.stack + "\n");
    }
    process.exit(1);
}

async function readStdin() {
    const chunks = [];
    for await (const chunk of process.stdin) {
        chunks.push(chunk);
    }
    const text = Buffer.concat(chunks).toString("utf8").trim();
    return text ? JSON.parse(text) : {};
}

function exec(cmd, args = [], options = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(cmd, args, { cwd: process.cwd(), env: process.env, shell: false });
        let stdout = "";
        let stderr = "";
        child.stdout.on("data", (d) => { stdout += d; });
        child.stderr.on("data", (d) => { stderr += d; });
        child.on("error", reject);
        child.on("close", (exitCode) => {
            const result = { stdout, stderr, exitCode };
            if (exitCode !== 0 && !options.allowFailure) {
                const error = new Error(`${cmd} exited with code ${exitCode}: ${stderr.trim()}`);
                error.result = result;
                reject(error);
            } else {
                resolve(result);
            }
        });
        if (options.input !== undefined) {
            child.stdin.write(String(options.input));
        }
        child.stdin.end();
    });
}

const modulePath = process.argv[2];
let inputs;
try {
    inputs = await readStdin();
} catch (error) {
    fail("Action failed: could not read inputs: " + error.message, error);
}

const ctx = {
    workDir: process.env.AXIOM_WORK_DIR || process.cwd(),
    apiUrl: process.env.AXIOM_API_URL || "",
    taskId: process.env.AXIOM_TASK_ID || "",
    log: (...args) => process.stderr.write(format(...args) + "\n"),
    exec,
};

let mod;
try {
    mod = await import(pathToFileURL(modulePath).href);
} catch (error) {
    fail("Action failed: " + error.message, error);
}
if (typeof mod.run !== "function") {
    fail("Action module does not export run()");
}

let result;
try {
    result = await mod.run(inputs, ctx);
} catch (error) {
    fail("Action failed: " + (error && error.message ? error.message : String(error)), error);
}
const type = result === null ? "null" : Array.isArray(result) ? "array" : typeof result;
if (type !== "object") {
    fail(`run() must return an object, got ${type}`);
}
process.stdout.write("\n" + JSON.stringify(result) + "\n");
