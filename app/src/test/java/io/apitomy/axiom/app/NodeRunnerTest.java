package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the bundled Node runner ({@code axiom-node-runner.mjs}) against small action modules with a real
 * {@code node} binary. Skipped when Node.js is not installed.
 */
class NodeRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void requireNode() {
        boolean available;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            available = p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "node is not installed");
    }

    private ScriptExecutionService.CapturedOutput run(String code, String stdin, Path workDir) throws Exception {
        Path codeDir = Files.createDirectories(tempDir.resolve("code"));
        Path runner = NodeExecutionService.writeRunner(codeDir);
        Path action = codeDir.resolve("action.mjs");
        Files.writeString(action, code);
        ProcessBuilder pb = new ProcessBuilder("node", runner.toString(), action.toString())
                .directory(workDir.toFile());
        pb.environment().put("AXIOM_WORK_DIR", workDir.toString());
        Process process = pb.start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        return ScriptExecutionService.capture(process, 30);
    }

    private ScriptExecutionService.CapturedOutput run(String code, String stdin) throws Exception {
        return run(code, stdin, Files.createDirectories(tempDir.resolve("work")));
    }

    @Test
    void typedInputsReachRunAndResultIsTheOutput() throws Exception {
        ScriptExecutionService.CapturedOutput out = run(
                "export async function run(inputs) {\n"
                        + "    return { joined: inputs.labels.join(\",\"), n: inputs.meta.n + inputs.count };\n"
                        + "}\n",
                "{\"labels\":[\"a\",\"b\"],\"meta\":{\"n\":1},\"count\":3}");
        assertEquals(0, out.exitCode(), out.stderr());
        assertEquals(MAPPER.readTree("{\"joined\":\"a,b\",\"n\":4}"),
                MAPPER.readTree(ScriptExecutionService.extractScriptOutput(out.stdout())));
    }

    @Test
    void logGoesToStderrOnly() throws Exception {
        ScriptExecutionService.CapturedOutput out = run(
                "export async function run(inputs, ctx) { ctx.log(\"hi {x}\"); return { ok: true }; }\n", "{}");
        assertEquals(0, out.exitCode(), out.stderr());
        assertTrue(out.stderr().contains("hi {x}"));
        assertFalse(out.stdout().contains("hi {x}"));
        assertEquals(MAPPER.readTree("{\"ok\":true}"),
                MAPPER.readTree(ScriptExecutionService.extractScriptOutput(out.stdout())));
    }

    @Test
    void execPassesArgumentsWithoutAShell() throws Exception {
        Path marker = tempDir.resolve("marker");
        ScriptExecutionService.CapturedOutput out = run(
                "export async function run(inputs, ctx) {\n"
                        + "    const a = await ctx.exec(\"echo\", [\"a b\"]);\n"
                        + "    await ctx.exec(\"echo\", [\"$(touch " + marker + ")\"]);\n"
                        + "    return { out: a.stdout.trim() };\n"
                        + "}\n", "{}");
        assertEquals(0, out.exitCode(), out.stderr());
        assertEquals("a b", MAPPER.readTree(ScriptExecutionService.extractScriptOutput(out.stdout()))
                .get("out").asText());
        assertFalse(Files.exists(marker));
    }

    @Test
    void execRejectsOnFailureUnlessAllowed() throws Exception {
        ScriptExecutionService.CapturedOutput out = run(
                "export async function run(inputs, ctx) {\n"
                        + "    let rejected = false;\n"
                        + "    try { await ctx.exec(\"false\"); } catch (e) { rejected = true; }\n"
                        + "    const r = await ctx.exec(\"false\", [], { allowFailure: true });\n"
                        + "    return { rejected, exitCode: r.exitCode };\n"
                        + "}\n", "{}");
        assertEquals(0, out.exitCode(), out.stderr());
        assertEquals(MAPPER.readTree("{\"rejected\":true,\"exitCode\":1}"),
                MAPPER.readTree(ScriptExecutionService.extractScriptOutput(out.stdout())));
    }

    @Test
    void thrownErrorFailsTheAction() throws Exception {
        ScriptExecutionService.CapturedOutput out = run(
                "export async function run() { throw new Error(\"boom\"); }\n", "{}");
        assertEquals(1, out.exitCode());
        assertEquals("Action failed: boom", out.stderr().lines().findFirst().orElse(""));
    }

    @Test
    void moduleWithoutRunFails() throws Exception {
        ScriptExecutionService.CapturedOutput out = run("export const x = 1;\n", "{}");
        assertEquals(1, out.exitCode());
        assertEquals("Action module does not export run()", out.stderr().lines().findFirst().orElse(""));
    }

    @Test
    void nonObjectResultsFail() throws Exception {
        ScriptExecutionService.CapturedOutput array = run(
                "export async function run() { return [1, 2]; }\n", "{}");
        assertEquals(1, array.exitCode());
        assertEquals("run() must return an object, got array", array.stderr().lines().findFirst().orElse(""));

        ScriptExecutionService.CapturedOutput string = run(
                "export async function run() { return \"x\"; }\n", "{}");
        assertEquals(1, string.exitCode());
        assertEquals("run() must return an object, got string",
                string.stderr().lines().findFirst().orElse(""));
    }

    @Test
    void runsInTheGivenWorkingDirectory() throws Exception {
        Path workDir = Files.createDirectories(tempDir.resolve("subject-ws")).toRealPath();
        ScriptExecutionService.CapturedOutput out = run(
                "export async function run(inputs, ctx) { return { wd: ctx.workDir, cwd: process.cwd() }; }\n",
                "{}", workDir);
        assertEquals(0, out.exitCode(), out.stderr());
        com.fasterxml.jackson.databind.JsonNode result =
                MAPPER.readTree(ScriptExecutionService.extractScriptOutput(out.stdout()));
        assertEquals(workDir.toString(), result.get("wd").asText());
        assertEquals(workDir.toString(), result.get("cwd").asText());
    }
}
