package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DraftTestRunner} against draft action type and tool JSON.
 */
@QuarkusTest
class DraftTestRunnerTest {

    @Inject
    DraftTestRunner runner;

    @Inject
    ObjectMapper mapper;

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    private DraftTestRunner.Result action(String content, Map<String, Object> inputs) throws Exception {
        JsonNode node = json(content);
        return QuarkusTransaction.requiringNew().call(() -> runner.runActionType(node, inputs, null));
    }

    @Test
    void scriptActionPassesWhenOutputsMatch() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script",
                 "inputs":[{"name":"who","type":"string","required":true}],
                 "outputs":[{"name":"greeting","type":"string","required":true}],
                 "scriptTemplate":"printf '{\\"greeting\\":\\"hi %s\\"}\\\\n' {{inputs.who}}"}""",
                Map.of("who", "bob"));
        assertEquals("passed", r.status(), r.toString());
        assertEquals("hi bob", r.outputs().get("greeting"));
        assertEquals(0, r.exitCode());
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void missingDeclaredOutputFails() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script","inputs":[],
                 "outputs":[{"name":"count","type":"number","required":true}],
                 "scriptTemplate":"echo '{}'"}""", Map.of());
        assertEquals("failed", r.status());
        assertEquals(List.of("Output: Missing required field 'count'."), r.problems());
    }

    @Test
    void nonZeroExitFails() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script","scriptTemplate":"echo boom >&2; exit 3"}""", Map.of());
        assertEquals("failed", r.status());
        assertEquals(3, r.exitCode());
        assertTrue(r.stderr().contains("boom"));
    }

    @Test
    void missingRequiredInputIsAnErrorAndDoesNotRun() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script",
                 "inputs":[{"name":"who","type":"string","required":true}],
                 "scriptTemplate":"echo ran"}""", Map.of());
        assertEquals("error", r.status());
        assertEquals(List.of("Input: Missing required field 'who'."), r.problems());
        assertNull(r.exitCode());
    }

    @Test
    void nodeActionReportsItsCodeAsTheResolvedScript() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed");
        String code = "export async function run(inputs, ctx) { return { n: 1 }; }";
        DraftTestRunner.Result r = action("{\"name\":\"n\",\"executionMode\":\"node\",\"codeTemplate\":"
                + mapper.writeValueAsString(code) + "}", Map.of());
        assertEquals("passed", r.status(), r.toString());
        assertEquals(code, r.resolvedScript());
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (java.io.IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void agentModeIsNotSupported() throws Exception {
        DraftTestRunner.Result r = action("{\"name\":\"a\",\"executionMode\":\"agent\",\"promptTemplate\":\"x\"}",
                Map.of());
        assertEquals("error", r.status());
        assertEquals(DraftTestRunner.AGENT_UNSUPPORTED, r.message());
    }

    @Test
    void emptyTemplateIsAnError() throws Exception {
        DraftTestRunner.Result r = action("{\"name\":\"n\",\"executionMode\":\"node\",\"codeTemplate\":\"  \"}",
                Map.of());
        assertEquals("error", r.status());
        assertEquals("The action type has no code to run.", r.message());
    }

    @Test
    void toolRunsWithSubstitutedParameters() throws Exception {
        JsonNode tool = json("""
                {"name":"t","parameters":[{"name":"who","required":true},{"name":"body","required":false},
                 {"name":"opt","required":false}],
                 "scriptTemplate":"echo \\"hello {{who}}\\"; cat {{body_file}}; echo \\"[{{opt}}]\\""}""");
        DraftTestRunner.Result r = QuarkusTransaction.requiringNew().call(() ->
                runner.runTool(tool, Map.of("who", "ann", "body", "long text"), null));
        assertEquals("passed", r.status(), r.toString());
        assertEquals("hello ann\nlong text[]\n", r.stdout());
        assertNull(r.outputs());
    }

    @Test
    void toolMissingRequiredParameterIsAnError() throws Exception {
        JsonNode tool = json("{\"name\":\"t\",\"parameters\":[{\"name\":\"who\",\"required\":true}],"
                + "\"scriptTemplate\":\"echo {{who}}\"}");
        DraftTestRunner.Result r = QuarkusTransaction.requiringNew().call(() ->
                runner.runTool(tool, Map.of(), null));
        assertEquals("error", r.status());
        assertEquals(List.of("Parameter: Missing required parameter 'who'."), r.problems());
    }

    @Test
    void renderToolScriptMatchesTheToolsServer(@TempDir Path dir) throws Exception {
        List<JsonNode> params = List.of(json("{\"name\":\"a\",\"required\":true}"),
                json("{\"name\":\"b\",\"required\":false}"));
        String script = DraftTestRunner.renderToolScript("x={{a}} f={{a_file}} y={{b}} z={{b_file}}",
                params, Map.of("a", "1"), dir);
        assertTrue(script.startsWith("x=1 f=" + dir), script);
        assertTrue(script.endsWith(" y= z="), script);
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertEquals("1", Files.readString(files.findFirst().orElseThrow()));
        }
    }

    @Test
    void stdoutWithoutTrailingJsonFails() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script","inputs":[],
                 "outputs":[{"name":"count","type":"number","required":true}],
                 "scriptTemplate":"echo hello"}""", Map.of());
        assertEquals("failed", r.status());
        assertTrue(r.problems().contains(DraftTestRunner.NO_JSON_OUTPUT), r.problems().toString());
    }

    @Test
    void stdoutWithoutJsonPassesWhenNoOutputsAreDeclared() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script","inputs":[],"outputs":[],
                 "scriptTemplate":"echo hello"}""", Map.of());
        assertEquals("passed", r.status(), r.toString());
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void slowToolTimesOut() throws Exception {
        JsonNode tool = json("{\"name\":\"t\",\"scriptTemplate\":\"sleep 5\"}");
        runner.setToolTimeoutSeconds(1);
        try {
            long start = System.currentTimeMillis();
            DraftTestRunner.Result r = QuarkusTransaction.requiringNew().call(() ->
                    runner.runTool(tool, Map.of(), null));
            assertEquals("failed", r.status());
            assertEquals(List.of("Timed out."), r.problems());
            assertTrue(System.currentTimeMillis() - start < 3000);
        } finally {
            runner.setToolTimeoutSeconds(DraftTestRunner.TOOL_TIMEOUT_SECONDS);
        }
    }

    @Test
    void longOutputIsTruncated() {
        String text = "x".repeat(DraftTestRunner.MAX_OUTPUT_CHARS + 10);
        assertTrue(DraftTestRunner.truncate(text).endsWith("\n[truncated]"));
        assertEquals(DraftTestRunner.MAX_OUTPUT_CHARS + "\n[truncated]".length(),
                DraftTestRunner.truncate(text).length());
    }
}
