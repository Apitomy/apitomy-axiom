package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.McpTestSupport;
import io.apitomy.axiom.app.mcp.McpServerDefinition;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interactive sessions start their MCP servers without the session's environment (#617): axiom-tools gets only
 * the Axiom-provided variables and each tool its own, and external stdio servers get only their own.
 */
@QuarkusTest
class SessionMcpEnvironmentIsolationTest {

    private static final Map<String, String> SESSION_ENV = Map.of(
            "SESSION_SECRET", "session-secret-value",
            "AXIOM_SUBJECT_NAME", "demo");

    @Inject
    AssistantSessionManager sessionManager;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            ToolDefinitionEntity probe = new ToolDefinitionEntity();
            probe.name = "siso_probe";
            probe.description = "Prints what it can see";
            probe.parameters = "[]";
            probe.scriptTemplate = "echo \"own=${OWN_VAR:-none} session=${SESSION_SECRET:-none}"
                    + " subject=${AXIOM_SUBJECT_NAME:-none}\"";
            probe.environment = "{\"OWN_VAR\":\"own-value\"}";
            probe.persist();

            McpServerEntity external = new McpServerEntity();
            external.name = "siso-external";
            external.serverCommand = "sh";
            external.serverArgs = "[\"-c\", \"env\"]";
            external.serverEnv = "{\"OWN\":\"server-own\"}";
            external.persist();
        });
    }

    @AfterEach
    void tearDown() {
        QuarkusTransaction.requiringNew().run(() -> {
            ToolDefinitionEntity.delete("name", "siso_probe");
            McpServerEntity.delete("name", "siso-external");
        });
    }

    private Map<String, McpServerDefinition> resolve() throws Exception {
        Path sessionDir = Files.createTempDirectory("siso-session-");
        SessionTemplateService.SessionTemplate template = new SessionTemplateService.SessionTemplate(
                "siso", "siso", "", "", null, null, null, null, null, null, null, null,
                List.of("@axiom-tools", "siso-external"), List.of(), false, Set.of(), Set.of());
        return QuarkusTransaction.requiringNew().call(() -> sessionManager.resolveMcpServers("s-1", "token",
                template, null, null, sessionDir, SESSION_ENV, List.of()));
    }

    private static List<String> commandOf(McpServerDefinition def) {
        List<String> cmd = new ArrayList<>();
        cmd.add(def.command());
        cmd.addAll(def.args());
        return cmd;
    }

    @Test
    void scriptToolSeesItsOwnEnvironmentButNotTheSessions() throws Exception {
        McpServerDefinition tools = resolve().get("axiom-tools");
        assertFalse(tools.env().containsKey("SESSION_SECRET"));
        Process process = McpTestSupport.startLikeCli(commandOf(tools), tools.env(), SESSION_ENV);
        try {
            assertEquals("own=own-value session=none subject=demo",
                    McpTestSupport.callTool(process, objectMapper, "siso_probe", Map.of()).trim());
        } finally {
            process.destroy();
        }
    }

    @Test
    void externalStdioServerDoesNotSeeTheSessionEnvironment() throws Exception {
        McpServerDefinition external = resolve().get("siso-external");
        Process process = McpTestSupport.startLikeCli(commandOf(external), external.env(), SESSION_ENV);
        process.getOutputStream().close();
        String env = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertTrue(env.contains("OWN=server-own"), env);
        assertFalse(env.contains("SESSION_SECRET"), env);
        assertFalse(env.contains("AXIOM_SUBJECT_NAME"), env);
    }
}
