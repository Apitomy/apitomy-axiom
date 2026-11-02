package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager.McpServerConfig;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.MissingSecretException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Script tools and stdio MCP servers get only their own declared environment, never the calling agent's
 * (#617). Servers are started the way an agent CLI starts them: with the CLI's whole environment (which
 * includes the agent's variables) merged with the server's configured {@code env}.
 */
@QuarkusTest
class McpEnvironmentIsolationTest {

    /** The calling agent's environment, as the agent CLI process would have it. */
    private static final Map<String, String> AGENT_ENV = Map.of(
            "AGENT_SECRET", "agent-secret-value",
            "ANTHROPIC_API_KEY", "sk-model-key",
            "AXIOM_TRACE_ID", "trace-617");

    private static final String PROBE_SCRIPT = "echo \"own=${OWN_VAR:-none} secret=${TOOL_SECRET:-none}"
            + " agent=${AGENT_SECRET:-none} model=${ANTHROPIC_API_KEY:-none} trace=${AXIOM_TRACE_ID:-none}"
            + " path=${PATH:+set}\"";

    private static final List<String> ALLOWED = List.of("mcp__axiom-tools__iso_probe", "mcp__axiom-tools__iso_other",
            "mcp__iso-external__*");

    @Inject
    McpConfigGenerator generator;

    @Inject
    EncryptionService encryptionService;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity secret = new SecretEntity();
            secret.name = "ISO_SECRET";
            secret.encryptedValue = encryptionService.encrypt("tool-secret-value");
            secret.persist();

            ToolDefinitionEntity probe = new ToolDefinitionEntity();
            probe.name = "iso_probe";
            probe.description = "Prints what it can see";
            probe.parameters = "[]";
            probe.scriptTemplate = PROBE_SCRIPT;
            probe.environment = "{\"OWN_VAR\":\"own-value\",\"TOOL_SECRET\":\"${secret:ISO_SECRET}\"}";
            probe.persist();

            ToolDefinitionEntity other = new ToolDefinitionEntity();
            other.name = "iso_other";
            other.description = "Declares nothing";
            other.parameters = "[]";
            other.scriptTemplate = PROBE_SCRIPT;
            other.persist();

            McpServerEntity external = new McpServerEntity();
            external.name = "iso-external";
            external.serverCommand = "sh";
            external.serverArgs = "[\"-c\", \"env\"]";
            external.serverEnv = "{\"OWN\":\"server-own\"}";
            external.persist();
        });
    }

    @AfterEach
    void tearDown() {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity.delete("name", "ISO_SECRET");
            ToolDefinitionEntity.delete("name like ?1", "iso_%");
            McpServerEntity.delete("name", "iso-external");
        });
    }

    private JsonNode taskServers(long taskId) throws Exception {
        Path config = generator.generateMcpConfig(taskId, AGENT_ENV, ALLOWED);
        assertNotNull(config);
        return objectMapper.readTree(Files.readString(config)).get("mcpServers");
    }

    private String callProbe(List<String> command, Map<String, String> configuredEnv, String tool)
            throws Exception {
        Process process = McpTestSupport.startLikeCli(command, configuredEnv, AGENT_ENV);
        try {
            return McpTestSupport.callTool(process, objectMapper, tool, Map.of()).trim();
        } finally {
            process.destroy();
        }
    }

    private static String runToCompletion(List<String> command, Map<String, String> configuredEnv)
            throws Exception {
        Process process = McpTestSupport.startLikeCli(command, configuredEnv, AGENT_ENV);
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        return out;
    }

    @Test
    void scriptToolSeesItsOwnEnvironmentButNotTheAgents() throws Exception {
        JsonNode tools = taskServers(61701L).get("axiom-tools");
        String output = callProbe(McpTestSupport.configuredCommand(tools), McpTestSupport.configuredEnv(tools),
                "iso_probe");
        assertEquals("own=own-value secret=tool-secret-value agent=none model=none trace=trace-617 path=set",
                output);
    }

    @Test
    void scriptToolDoesNotSeeAnotherToolsEnvironment() throws Exception {
        JsonNode tools = taskServers(61702L).get("axiom-tools");
        String output = callProbe(McpTestSupport.configuredCommand(tools), McpTestSupport.configuredEnv(tools),
                "iso_other");
        assertEquals("own=none secret=none agent=none model=none trace=trace-617 path=set", output);
    }

    @Test
    void externalStdioServerSeesOnlyItsOwnEnvironmentAndThePassthrough() throws Exception {
        JsonNode external = taskServers(61703L).get("iso-external");
        String env = runToCompletion(McpTestSupport.configuredCommand(external),
                McpTestSupport.configuredEnv(external));
        assertTrue(env.contains("OWN=server-own"), env);
        assertTrue(env.contains("PATH="), env);
        assertFalse(env.contains("AGENT_SECRET"), env);
        assertFalse(env.contains("ANTHROPIC_API_KEY"), env);
        assertFalse(env.contains("AXIOM_TRACE_ID"), env);
    }

    @Test
    void openCodeServersAreIsolatedToo() throws Exception {
        List<McpServerConfig> configs = generator.getMcpServersForOpenCode(61704L, AGENT_ENV, ALLOWED);
        McpServerConfig external = configs.stream().filter(c -> c.name().equals("iso-external")).findFirst()
                .orElseThrow();
        String env = runToCompletion(external.command(), external.environment());
        assertTrue(env.contains("OWN=server-own"), env);
        assertFalse(env.contains("AGENT_SECRET"), env);
        assertFalse(env.contains("ANTHROPIC_API_KEY"), env);

        McpServerConfig tools = configs.stream().filter(c -> c.name().equals("axiom-tools")).findFirst()
                .orElseThrow();
        assertFalse(tools.environment().containsKey("AGENT_SECRET"));
        assertEquals("own=own-value secret=tool-secret-value agent=none model=none trace=trace-617 path=set",
                callProbe(tools.command(), tools.environment(), "iso_probe"));
    }

    @Test
    void toolsFileIsOwnerOnly() throws Exception {
        JsonNode tools = taskServers(61705L).get("axiom-tools");
        Path toolsJson = Path.of(McpTestSupport.realCommand(tools).get(2));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(toolsJson)));
    }

    @Test
    void missingSecretInAToolsEnvironmentFailsTheConfig() {
        QuarkusTransaction.requiringNew().run(() -> {
            ToolDefinitionEntity probe = ToolDefinitionEntity.find("name", "iso_probe").firstResult();
            probe.environment = "{\"TOKEN\":\"${secret:ISO_DOES_NOT_EXIST}\"}";
        });
        MissingSecretException e = assertThrows(MissingSecretException.class,
                () -> generator.generateMcpConfig(61706L, AGENT_ENV, ALLOWED));
        assertTrue(e.getMessage().contains("iso_probe"), e.getMessage());
    }
}
