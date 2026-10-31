package io.apitomy.axiom.agents.opencode;

import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager.McpServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that the OpenCode task MCP manager keeps the OpenCode server's MCP registrations in sync with
 * Axiom's MCP server definitions.
 */
class OpenCodeMcpManagerSyncTest {

    private final List<String> calls = new ArrayList<>();
    private final OpenCodeMcpManager.McpRegistrar registrar = new OpenCodeMcpManager.McpRegistrar() {
        @Override
        public void add(String name, Map<String, Object> config) {
            calls.add("add " + name + " " + config.get("command"));
        }

        @Override
        public void disconnect(String name) {
            calls.add("disconnect " + name);
        }
    };

    private OpenCodeMcpManager manager;
    private final Object server = new Object();

    @BeforeEach
    void setUp() {
        manager = new OpenCodeMcpManager();
    }

    private static McpServerConfig local(String name, String... command) {
        return McpServerConfig.local(name, List.of(command), Map.of());
    }

    @Test
    void unchangedServerIsRegisteredOnce() {
        manager.sync(server, registrar, List.of(local("gh", "npx", "a")), Set.of("gh"));
        manager.sync(server, registrar, List.of(local("gh", "npx", "a")), Set.of("gh"));
        assertEquals(List.of("add gh [npx, a]"), calls);
    }

    @Test
    void changedServerIsReRegistered() {
        manager.sync(server, registrar, List.of(local("gh", "npx", "a")), Set.of("gh"));
        manager.sync(server, registrar, List.of(local("gh", "npx", "b")), Set.of("gh"));
        assertEquals(List.of("add gh [npx, a]", "add gh [npx, b]"), calls);
    }

    @Test
    void deletedOrDisabledServerIsDisconnected() {
        manager.sync(server, registrar, List.of(local("gh", "npx"), local("jira", "npx")), Set.of("gh", "jira"));
        calls.clear();
        manager.sync(server, registrar, List.of(local("gh", "npx")), Set.of("gh"));
        assertEquals(List.of("disconnect jira"), calls);
    }

    @Test
    void serverNotNeededByThisTaskButStillDefinedStaysRegistered() {
        manager.sync(server, registrar, List.of(local("gh", "npx"), local("jira", "npx")), Set.of("gh", "jira"));
        calls.clear();
        manager.sync(server, registrar, List.of(local("gh", "npx")), Set.of("gh", "jira"));
        assertEquals(List.of(), calls);
    }

    @Test
    void reDefinedServerIsRegisteredAgainAfterDisconnect() {
        manager.sync(server, registrar, List.of(local("gh", "npx")), Set.of("gh"));
        manager.sync(server, registrar, List.of(), Set.of());
        manager.sync(server, registrar, List.of(local("gh", "npx")), Set.of("gh"));
        assertEquals(List.of("add gh [npx]", "disconnect gh", "add gh [npx]"), calls);
    }

    @Test
    void builtInServersAreRegisteredOnceAndNeverDisconnected() {
        manager.sync(server, registrar, List.of(local("axiom-tools", "node", "/t/1/tools.json")), Set.of());
        manager.sync(server, registrar, List.of(local("axiom-tools", "node", "/t/2/tools.json")), Set.of());
        assertEquals(List.of("add axiom-tools [node, /t/1/tools.json]"), calls);
    }

    @Test
    void newOpenCodeServerInstanceGetsEverythingRegisteredAgain() {
        manager.sync(server, registrar, List.of(local("gh", "npx")), Set.of("gh"));
        manager.sync(new Object(), registrar, List.of(local("gh", "npx")), Set.of("gh"));
        assertEquals(List.of("add gh [npx]", "add gh [npx]"), calls);
    }
}
