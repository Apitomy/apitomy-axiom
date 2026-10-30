package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.McpServerEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link McpServerResolver}'s entity-to-definition conversion and selection logic.
 */
class McpServerResolverTest {

    private final McpServerResolver resolver = new McpServerResolver(new ObjectMapper());

    private static McpServerEntity entity(String name, String command, String args, String env, String url) {
        McpServerEntity e = new McpServerEntity();
        e.name = name;
        e.serverCommand = command;
        e.serverArgs = args;
        e.serverEnv = env;
        e.serverUrl = url;
        return e;
    }

    @Test
    void stdioEntityBecomesStdioDefinition() {
        Optional<McpServerDefinition> def = resolver.toDefinition(
                entity("gh", "npx", "[\"-y\",\"pkg\"]", "{\"TOKEN\":\"t\"}", null));
        assertTrue(def.isPresent());
        assertFalse(def.get().isHttpTransport());
        assertEquals("npx", def.get().command());
        assertEquals(List.of("-y", "pkg"), def.get().args());
        assertEquals(Map.of("TOKEN", "t"), def.get().env());
    }

    @Test
    void urlEntityBecomesHttpDefinition() {
        McpServerDefinition def = resolver.toDefinition(entity("r", null, null, null, "https://x.io/mcp")).orElseThrow();
        assertTrue(def.isHttpTransport());
        assertEquals("https://x.io/mcp", def.url());
    }

    @Test
    void entityWithNeitherIsSkipped() {
        assertTrue(resolver.toDefinition(entity("none", null, null, null, " ")).isEmpty());
    }

    @Test
    void malformedArgsOrEnvSkipServer() {
        assertTrue(resolver.toDefinition(entity("bad", "npx", "not-json", null, null)).isEmpty());
        assertTrue(resolver.toDefinition(entity("bad", "npx", null, "[1]", null)).isEmpty());
    }

    @Test
    void selectsServersByAllowedToolPrefix() {
        List<McpServerEntity> all = List.of(entity("gh", "npx", null, null, null),
                entity("jira", "npx", null, null, null), entity("gh-ent", "npx", null, null, null));
        List<String> names = McpServerResolver.selectByAllowedTools(all, List.of("mcp__gh__list", "Read"))
                .stream().map(e -> e.name).toList();
        assertEquals(List.of("gh"), names);
    }

    @Test
    void extractsServerNameFromToolName() {
        assertEquals(Optional.of("gh"), McpServerResolver.serverNameOf("mcp__gh__list_repos"));
        assertEquals(Optional.of("gh"), McpServerResolver.serverNameOf("mcp__gh__*"));
        assertEquals(Optional.empty(), McpServerResolver.serverNameOf("Read"));
        assertEquals(Optional.empty(), McpServerResolver.serverNameOf("mcp__gh"));
    }
    @Test
    void disabledServerIsSkipped() {
        McpServerEntity e = entity("off", "npx", null, null, null);
        e.enabled = false;
        assertTrue(resolver.toDefinition(e).isEmpty());
    }

    @Test
    void httpServerCarriesHeadersTimeoutAndSseType() {
        McpServerEntity e = entity("r", null, null, null, "https://x.io/sse");
        e.transport = "sse";
        e.headers = "{\"Authorization\":\"Bearer t\"}";
        e.timeoutMs = 5000;
        McpServerDefinition def = resolver.toDefinition(e).orElseThrow();
        assertTrue(def.isHttpTransport());
        assertEquals("sse", def.type());
        assertEquals(Map.of("Authorization", "Bearer t"), def.headers());
        assertEquals(5000, def.timeoutMs());
    }
}
