package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.apitomy.axiom.api.beans.McpCatalogEntry;
import io.apitomy.axiom.api.beans.McpCatalogInput;
import io.apitomy.axiom.api.beans.NewMcpServer;
import io.apitomy.axiom.api.beans.ServerEnv;
import io.apitomy.axiom.api.beans.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the curated MCP catalog and the mapping of MCP Registry entries.
 */
class McpCatalogServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer http;

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    private static McpCatalogService service(String registryUrl) {
        return new McpCatalogService(MAPPER, registryUrl);
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream in = McpCatalogServiceTest.class.getResourceAsStream("/mcp/registry-fixture.json")) {
            return MAPPER.readTree(in);
        }
    }

    private static McpCatalogEntry byId(List<McpCatalogEntry> entries, String id) {
        return entries.stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }

    /** Builds a server from a catalog entry with a dummy value for every input, as the UI does. */
    private static NewMcpServer toServer(McpCatalogEntry e) {
        NewMcpServer s = new NewMcpServer();
        s.setName(e.getName());
        s.setTransport(NewMcpServer.Transport.fromValue(e.getTransport().value()));
        s.setServerCommand(e.getCommand());
        s.setServerUrl(e.getUrl());
        List<String> args = new ArrayList<>();
        for (String a : e.getArgs()) {
            args.add(a.replaceAll("\\{arg:[^}]+}", "/tmp"));
        }
        s.setServerArgs(args);
        if (!e.getEnv().isEmpty()) {
            ServerEnv env = new ServerEnv();
            e.getEnv().forEach(i -> env.setAdditionalProperty(i.getName(), "x"));
            s.setServerEnv(env);
        }
        if (!e.getHeaders().isEmpty()) {
            Headers headers = new Headers();
            e.getHeaders().forEach(i -> headers.setAdditionalProperty(i.getName(), "x"));
            s.setHeaders(headers);
        }
        return s;
    }

    @Test
    void curatedEntriesAreValidServers() {
        List<McpCatalogEntry> curated = service(null).curated(null);
        assertTrue(curated.size() >= 10, "curated catalog is unexpectedly small");
        for (McpCatalogEntry e : curated) {
            McpServerService.validateDefinition(toServer(e));
            assertEquals(McpCatalogEntry.Source.CURATED, e.getSource());
            assertTrue(e.getRuntime() != null, e.getId());
        }
        McpCatalogEntry github = byId(curated, "github");
        assertEquals(McpCatalogEntry.Runtime.REMOTE, github.getRuntime());
        McpCatalogInput auth = github.getHeaders().get(0);
        assertTrue(auth.getSecret());
        assertEquals("Bearer {value}", auth.getTemplate());
        assertEquals(McpCatalogEntry.Runtime.UV, byId(curated, "atlassian").getRuntime());
    }

    @Test
    void curatedSearchMatchesNameTitleAndDescription() {
        McpCatalogService svc = service(null);
        assertEquals(List.of("atlassian"), svc.curated("jira").stream().map(McpCatalogEntry::getId).toList());
        assertTrue(svc.curated("GITHUB").stream().anyMatch(e -> e.getId().equals("github")));
        assertTrue(svc.curated("no-such-thing-xyz").isEmpty());
    }

    @Test
    void registryRemoteIsPreferredOverDocker() throws Exception {
        McpCatalogEntry github = byId(McpCatalogService.fromRegistry(fixture()), "io.github.github/github-mcp-server");
        assertEquals(McpCatalogEntry.Source.REGISTRY, github.getSource());
        assertEquals("github-mcp-server", github.getName());
        assertEquals(McpCatalogEntry.Transport.HTTP, github.getTransport());
        assertEquals("https://api.githubcopilot.com/mcp/", github.getUrl());
        McpCatalogInput auth = github.getHeaders().get(0);
        assertEquals("Authorization", auth.getName());
        assertTrue(auth.getSecret());
        assertEquals("Bearer {value}", auth.getTemplate());
    }

    @Test
    void registryNpmPackageRunsWithNpx() throws Exception {
        McpCatalogEntry c7 = byId(McpCatalogService.fromRegistry(fixture()), "io.github.upstash/context7");
        assertEquals(McpCatalogEntry.Transport.STDIO, c7.getTransport());
        assertEquals("npx", c7.getCommand());
        assertEquals(List.of("-y", "@upstash/context7-mcp@4.4.4"), c7.getArgs());
        assertEquals(McpCatalogEntry.Runtime.NODE, c7.getRuntime());
        assertEquals("CONTEXT7_API_KEY", c7.getEnv().get(0).getName());
        assertTrue(c7.getEnv().get(0).getSecret());
        assertFalse(c7.getEnv().get(0).getRequired());
    }

    @Test
    void registryHeaderTemplatesUseValuePlaceholder() throws Exception {
        McpCatalogEntry fork = byId(McpCatalogService.fromRegistry(fixture()), "ai.smithery/renCosta2025-context7fork");
        assertEquals("Bearer {value}", fork.getHeaders().get(0).getTemplate());
    }

    @Test
    void registryPypiPackageRunsWithUvxAndExposesRequiredArguments() throws Exception {
        McpCatalogEntry time = byId(McpCatalogService.fromRegistry(fixture()), "io.example/time");
        assertEquals("uvx", time.getCommand());
        assertEquals(List.of("mcp-server-time==2026.10.28", "--local-timezone", "UTC", "{arg:data_dir}"), time.getArgs());
        assertEquals("data_dir", time.getArguments().get(0).getName());
        assertTrue(time.getArguments().get(0).getRequired());
        assertEquals(McpCatalogEntry.Runtime.UV, time.getRuntime());
        assertNull(time.getUrl());
    }

    @Test
    void searchesTheRegistryOverHttp() throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(fixture());
        AtomicReference<String> query = new AtomicReference<>();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/v0/servers", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.start();
        List<McpCatalogEntry> entries = service("http://127.0.0.1:" + http.getAddress().getPort())
                .registry("git hub");
        assertEquals(4, entries.size());
        assertTrue(query.get().contains("search=git+hub") || query.get().contains("search=git%20hub"), query.get());
        assertTrue(query.get().contains("version=latest"), query.get());
    }

    @Test
    void unreachableRegistryFailsClearly() {
        McpCatalogService.RegistryUnavailableException e = assertThrows(
                McpCatalogService.RegistryUnavailableException.class,
                () -> service("http://127.0.0.1:1").registry("x"));
        assertTrue(e.getMessage().contains("MCP Registry"), e.getMessage());
    }

    @Test
    void reportsInstalledRuntimes() {
        Map<String, Boolean> runtimes = McpCatalogService.findRuntimes(System.getenv("PATH"));
        assertTrue(runtimes.get("node"), "npx should be on the PATH of the test environment");
        assertEquals(Map.of("node", false, "uv", false, "docker", false), McpCatalogService.findRuntimes(""));
    }
}
