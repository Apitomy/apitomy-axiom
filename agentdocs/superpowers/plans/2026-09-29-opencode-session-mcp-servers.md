# OpenCode Session MCP Servers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Expose the MCP servers configured for an assistant session to the per-session `opencode serve`
process, and warn the user when any of them fail to connect (GitHub issue #368, epic #387).

**Architecture:** `AssistantSessionManager` already resolves the session's MCP servers into a
`Map<String, AssistantContextBuilder.McpServerConfig>`. We pass that map through `DriverRequest` to the
factory. For opencode, the factory writes `<sessionDir>/opencode.json` with an `mcp` block and starts
`opencode serve` with `OPENCODE_CONFIG=<that file>`. After the session starts, the driver calls `GET /mcp`
and emits a `session_error` event (rendered by the UI as a system message) for each expected server that is
not `connected`. The Claude Code path is untouched.

**Tech Stack:** Java 21, Quarkus, Jackson, JUnit 5, JDK `HttpServer` for fakes, opencode 1.18.x.

**Spec:** GitHub issue #368 ("OpenCode sessions: expose session MCP servers to opencode").

## Background (verified facts)

- Verified against opencode 1.18.33: starting `opencode serve` with `OPENCODE_CONFIG=/path/file.json` loads
  the `mcp` block from that file. The user's global `~/.config/opencode/opencode.json` MCP servers are
  merged in as well (same as Claude Code loading user config).
- `GET /mcp` returns immediately after health is OK, with final statuses, e.g.:
  ```json
  {"bogus":{"status":"failed","error":"ENOENT: no such file or directory, posix_spawn '/nonexistent/cmd'"},
   "rem":{"status":"failed","error":"SSE error: Unable to connect. Is the computer able to access the url?"},
   "chrome-devtools":{"status":"connected"}}
  ```
  Other documented statuses: `disabled`, `needs_auth`, `needs_client_registration`.
- opencode MCP config shapes:
  - local: `{"type":"local","command":["cmd","arg1"],"environment":{"K":"V"},"enabled":true}`
  - remote: `{"type":"remote","url":"http://...","enabled":true}`
- `McpServerConfig` (`app/src/main/java/io/apitomy/axiom/app/assistant/AssistantContextBuilder.java:152-177`)
  has `command()`, `args()`, `env()`, `type()`, `url()`, `isHttpTransport()`; factories `stdio(...)`,
  `http(url)`.
- The UI renders `session_error` as a system message (`ui/src/components/assistant/AssistantChatPanel.tsx:452`).
  `AssistantSession.handleDriverEvent` does not change session status on `session_error`.

## Global Constraints

- 4-space indentation; Javadoc on all public types and methods; explicit types (no `var`); JUnit 5.
- Do not change Claude Code behavior or the REST API / OpenAPI spec.
- Out of scope (separate issues): working directory and template environment (#369), system prompt (#370),
  allowed-tools/permission mapping (#371). Only add `OPENCODE_CONFIG` to the opencode process environment here.
- Commit message style: conventional commits, e.g. `fix(assistant): ...`. No AI attribution.
- Run tests with: `mvn -q -pl app test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`
  (run `mvn -q install -DskipTests` from the repo root once first if sibling modules aren't installed).

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeConfigWriter.java` | Create | Build/write the opencode config JSON (`mcp` block) |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java` | Modify | Accept extra environment for `opencode serve` |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java` | Modify | Add `mcpStatus()` (`GET /mcp`) |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java` | Modify | Report non-connected expected MCP servers after start |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java` | Modify | Carry MCP servers in `DriverRequest`; write config and wire env |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` | Modify | Pass `mcpConfigs` into `DriverRequest` |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeConfigWriterTest.java` | Create | Unit tests for config JSON |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClientMcpStatusTest.java` | Create | Unit test for `mcpStatus()` |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java` | Modify | Env wiring + real-opencode MCP integration test |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java` | Modify | MCP status warning tests |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java` | Modify | New `DriverRequest` arg; config file + env test |
| `docs/user-guide/ai-assistant.md` | Modify | Note MCP support/warnings for OpenCode |

---

### Task 1: OpenCode config writer

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeConfigWriter.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeConfigWriterTest.java`

**Interfaces:**
- Consumes: `AssistantContextBuilder.McpServerConfig`
- Produces:
  - `public static final String OpenCodeConfigWriter.CONFIG_FILE_NAME = "opencode.json"`
  - `public static ObjectNode OpenCodeConfigWriter.buildConfig(Map<String, McpServerConfig> servers)`
  - `public static Path OpenCodeConfigWriter.writeConfig(Path sessionDirectory, Map<String, McpServerConfig> servers) throws IOException`
    — returns `null` (writes nothing) when `servers` is null or empty.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeConfigWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildsLocalServerWithCommandArgsAndEnvironment() {
        Map<String, McpServerConfig> servers = Map.of("axiom",
                McpServerConfig.stdio("node", List.of("/home/u/.axiom/assistant-mcp-server/server.js"),
                        Map.of("AXIOM_API_URL", "http://localhost:9090/api/v1")));

        JsonNode config = OpenCodeConfigWriter.buildConfig(servers);

        JsonNode axiom = config.path("mcp").path("axiom");
        assertEquals("local", axiom.path("type").asText());
        assertEquals("node", axiom.path("command").get(0).asText());
        assertEquals("/home/u/.axiom/assistant-mcp-server/server.js", axiom.path("command").get(1).asText());
        assertEquals(2, axiom.path("command").size());
        assertEquals("http://localhost:9090/api/v1", axiom.path("environment").path("AXIOM_API_URL").asText());
        assertTrue(axiom.path("enabled").asBoolean());
        assertEquals("https://opencode.ai/config.json", config.path("$schema").asText());
    }

    @Test
    void omitsEnvironmentWhenEmpty() {
        JsonNode config = OpenCodeConfigWriter.buildConfig(
                Map.of("plain", McpServerConfig.stdio("npx", List.of("-y", "some-mcp"), Map.of())));

        assertFalse(config.path("mcp").path("plain").has("environment"));
    }

    @Test
    void buildsRemoteServerForHttpTransport() {
        JsonNode config = OpenCodeConfigWriter.buildConfig(
                Map.of("remote", McpServerConfig.http("http://127.0.0.1:8000/mcp")));

        JsonNode remote = config.path("mcp").path("remote");
        assertEquals("remote", remote.path("type").asText());
        assertEquals("http://127.0.0.1:8000/mcp", remote.path("url").asText());
        assertTrue(remote.path("enabled").asBoolean());
        assertFalse(remote.has("command"));
    }

    @Test
    void writesConfigFileToSessionDirectory(@TempDir Path sessionDir) throws Exception {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        servers.put("remote", McpServerConfig.http("http://127.0.0.1:8000/mcp"));

        Path written = OpenCodeConfigWriter.writeConfig(sessionDir, servers);

        assertEquals(sessionDir.resolve("opencode.json"), written);
        JsonNode parsed = MAPPER.readTree(Files.readString(written));
        assertEquals("remote", parsed.path("mcp").path("remote").path("type").asText());
    }

    @Test
    void writesNothingWhenNoServers(@TempDir Path sessionDir) throws Exception {
        assertNull(OpenCodeConfigWriter.writeConfig(sessionDir, Map.of()));
        assertNull(OpenCodeConfigWriter.writeConfig(sessionDir, null));
        assertFalse(Files.exists(sessionDir.resolve("opencode.json")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app test -Dtest=OpenCodeConfigWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class OpenCodeConfigWriter`.

- [ ] **Step 3: Write the implementation**

```java
package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder.McpServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Builds and writes the per-session OpenCode configuration file. The file is passed to
 * {@code opencode serve} via the {@code OPENCODE_CONFIG} environment variable and currently
 * contains the session's MCP servers.
 */
public final class OpenCodeConfigWriter {

    /** File name of the generated config inside the session directory. */
    public static final String CONFIG_FILE_NAME = "opencode.json";

    private static final String SCHEMA_URL = "https://opencode.ai/config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenCodeConfigWriter() {
    }

    /**
     * Builds an OpenCode config document containing an {@code mcp} block for the given servers.
     *
     * @param servers MCP servers keyed by server name; may be null
     * @return the config document
     */
    public static ObjectNode buildConfig(Map<String, McpServerConfig> servers) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", SCHEMA_URL);
        ObjectNode mcp = root.putObject("mcp");
        if (servers == null) {
            return root;
        }
        servers.forEach((String name, McpServerConfig config) -> mcp.set(name, toServerNode(config)));
        return root;
    }

    /**
     * Writes {@value #CONFIG_FILE_NAME} into the session directory when there are MCP servers.
     * The file may contain secrets (server environment), so it is made owner-readable only where
     * POSIX permissions are supported.
     *
     * @param sessionDirectory Axiom session directory
     * @param servers MCP servers keyed by server name; may be null
     * @return path of the written file, or {@code null} if there were no servers
     * @throws IOException if the file cannot be written
     */
    public static Path writeConfig(Path sessionDirectory, Map<String, McpServerConfig> servers)
            throws IOException {
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        Path file = sessionDirectory.resolve(CONFIG_FILE_NAME);
        Files.writeString(file, buildConfig(servers).toPrettyString());
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem; leave default permissions.
        }
        return file;
    }

    private static ObjectNode toServerNode(McpServerConfig config) {
        ObjectNode node = MAPPER.createObjectNode();
        if (config.isHttpTransport()) {
            node.put("type", "remote");
            node.put("url", config.url());
        } else {
            node.put("type", "local");
            ArrayNode command = node.putArray("command");
            command.add(config.command());
            config.args().forEach(command::add);
            if (config.env() != null && !config.env().isEmpty()) {
                ObjectNode environment = node.putObject("environment");
                config.env().forEach(environment::put);
            }
        }
        node.put("enabled", true);
        return node;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl app test -Dtest=OpenCodeConfigWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeConfigWriter.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeConfigWriterTest.java
git commit -m "feat(assistant): add OpenCode session config writer for MCP servers (#368)"
```

---

### Task 2: Pass environment to `opencode serve`

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java:37-77`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java`

**Interfaces:**
- Produces:
  - New constructor `OpenCodeSessionServerProcess(String executable, String hostname, int configuredPort, int startupTimeoutSeconds, Map<String, String> environment)`.
    The existing 4-arg constructor delegates with `Map.of()`.
  - Package-private `ProcessBuilder createProcessBuilder(int port)` (used by `start()` and tests).

- [ ] **Step 1: Write the failing test** (add to `OpenCodeSessionServerProcessTest`; add imports
  `java.util.List`, `java.util.Map`, `static org.junit.jupiter.api.Assertions.assertEquals`)

```java
    @Test
    void processBuilderIncludesExtraEnvironmentAndServeArguments() {
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of("OPENCODE_CONFIG", "/tmp/s/opencode.json"));

        ProcessBuilder builder = process.createProcessBuilder(4321);

        assertEquals(List.of("opencode", "serve", "--hostname", "127.0.0.1", "--port", "4321"),
                builder.command());
        assertEquals("/tmp/s/opencode.json", builder.environment().get("OPENCODE_CONFIG"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app test -Dtest=OpenCodeSessionServerProcessTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (no 5-arg constructor / `createProcessBuilder`).

- [ ] **Step 3: Implement**

Add field and constructors (add `import java.util.Map;`):

```java
    private final Map<String, String> environment;

    /**
     * Creates a per-session OpenCode server process manager with no extra environment.
     *
     * @param executable OpenCode executable name or path
     * @param hostname host to bind
     * @param configuredPort explicit port or {@code 0} for ephemeral
     * @param startupTimeoutSeconds startup timeout in seconds
     */
    public OpenCodeSessionServerProcess(String executable,
                                        String hostname,
                                        int configuredPort,
                                        int startupTimeoutSeconds) {
        this(executable, hostname, configuredPort, startupTimeoutSeconds, Map.of());
    }

    /**
     * Creates a per-session OpenCode server process manager.
     *
     * @param executable OpenCode executable name or path
     * @param hostname host to bind
     * @param configuredPort explicit port or {@code 0} for ephemeral
     * @param startupTimeoutSeconds startup timeout in seconds
     * @param environment extra environment variables for the server process (e.g. {@code OPENCODE_CONFIG})
     */
    public OpenCodeSessionServerProcess(String executable,
                                        String hostname,
                                        int configuredPort,
                                        int startupTimeoutSeconds,
                                        Map<String, String> environment) {
        this.executable = executable;
        this.hostname = hostname;
        this.configuredPort = configuredPort;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.environment = environment != null ? Map.copyOf(environment) : Map.of();
    }
```

Replace the inline `ProcessBuilder` construction in `start()` with
`ProcessBuilder processBuilder = createProcessBuilder(resolvedPort);` and add:

```java
    ProcessBuilder createProcessBuilder(int port) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                executable,
                "serve",
                "--hostname", hostname,
                "--port", String.valueOf(port)
        );
        processBuilder.environment().putAll(environment);
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }
```

(Remove the now-duplicate `processBuilder.redirectErrorStream(true);` line from `start()`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl app test -Dtest=OpenCodeSessionServerProcessTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the existing real-opencode test is skipped if opencode isn't installed).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java
git commit -m "feat(assistant): allow extra environment for the OpenCode session server (#368)"
```

---

### Task 3: `GET /mcp` status in the client

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClientMcpStatusTest.java`

**Interfaces:**
- Produces:
  - `public Map<String, McpServerStatus> OpenCodeAssistantClient.mcpStatus()` — throws `IllegalStateException`
    on non-200 or IO failure.
  - `public record OpenCodeAssistantClient.McpServerStatus(String status, String error)` with
    `public boolean connected()` (true iff `"connected".equals(status)`).

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeAssistantClientMcpStatusTest {

    // Captured from opencode 1.18.33
    private static final String MCP_RESPONSE = "{\"axiom\":{\"status\":\"connected\"},"
            + "\"bogus\":{\"status\":\"failed\",\"error\":\"ENOENT: no such file or directory\"}}";

    @Test
    void parsesMcpStatusResponse() throws Exception {
        HttpServer server = startServer(200, MCP_RESPONSE);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));

            Map<String, OpenCodeAssistantClient.McpServerStatus> statuses = client.mcpStatus();

            assertEquals(2, statuses.size());
            assertTrue(statuses.get("axiom").connected());
            assertNull(statuses.get("axiom").error());
            assertFalse(statuses.get("bogus").connected());
            assertEquals("failed", statuses.get("bogus").status());
            assertEquals("ENOENT: no such file or directory", statuses.get("bogus").error());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsOnHttpError() throws Exception {
        HttpServer server = startServer(500, "{}");
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertThrows(IllegalStateException.class, client::mcpStatus);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app test -Dtest=OpenCodeAssistantClientMcpStatusTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`mcpStatus` / `McpServerStatus` not found).

- [ ] **Step 3: Implement** (add imports `java.util.LinkedHashMap`, `java.util.Map`, `java.util.Iterator`)

Add after `respondPermission(...)`:

```java
    /**
     * Returns the connection status of every MCP server known to the OpenCode server.
     *
     * @return statuses keyed by MCP server name
     * @throws IllegalStateException if the request fails
     */
    public Map<String, McpServerStatus> mcpStatus() {
        JsonNode body = getJson("/mcp");
        Map<String, McpServerStatus> statuses = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            statuses.put(field.getKey(), new McpServerStatus(
                    value.path("status").asText(""),
                    value.hasNonNull("error") ? value.path("error").asText() : null));
        }
        return statuses;
    }
```

Add next to `postJson(...)`:

```java
    private JsonNode getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "OpenCode request failed: " + path + " -> HTTP " + response.statusCode());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("OpenCode request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during OpenCode request: " + path, e);
        }
    }
```

Add next to `HealthStatus`:

```java
    /**
     * Connection status of a single MCP server as reported by {@code GET /mcp}.
     *
     * @param status status value ({@code connected}, {@code failed}, {@code disabled}, {@code needs_auth}, ...)
     * @param error error message when available, otherwise null
     */
    public record McpServerStatus(String status, String error) {

        /**
         * @return true when the server is connected
         */
        public boolean connected() {
            return "connected".equals(status);
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl app test -Dtest=OpenCodeAssistantClientMcpStatusTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClientMcpStatusTest.java
git commit -m "feat(assistant): query OpenCode MCP server status (#368)"
```

---

### Task 4: Driver reports unavailable MCP servers

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java`

**Interfaces:**
- Consumes: `OpenCodeAssistantClient.mcpStatus()`, `McpServerStatus` (Task 3).
- Produces:
  - New public constructor (9 args) — the existing 8-arg public constructor plus a trailing
    `Set<String> expectedMcpServers`. The existing 8-arg public constructor delegates with `Set.of()`.
  - The package-private constructor gains a trailing `Set<String> expectedMcpServers` parameter; add a
    package-private overload with the old signature that delegates with `Set.of()` so existing tests compile.
  - Emits `SseEvent("session_error", {"name":"McpServerUnavailable","message":...})` via `eventSink` for each
    expected server that is missing or not connected. Session keeps `RUNNING`.

- [ ] **Step 1: Write the failing tests**

In `FakeOpenCodeServer`, add an optional MCP response. Change `start(EventResponder, CountDownLatch)` to
delegate to a new overload and register `/mcp`:

```java
        static FakeOpenCodeServer start(EventResponder eventResponder,
                                        CountDownLatch promptSubmitted) throws IOException {
            return start(eventResponder, promptSubmitted, "{}");
        }

        static FakeOpenCodeServer startWithMcp(String mcpResponse) throws IOException {
            return start(exchange -> new EventHandler().handle(exchange), null, mcpResponse);
        }

        static FakeOpenCodeServer start(EventResponder eventResponder,
                                        CountDownLatch promptSubmitted,
                                        String mcpResponse) throws IOException {
            // ... existing body unchanged, plus before server.start():
            server.createContext("/mcp", new JsonHandler(200, mcpResponse));
            // ...
        }
```

Add tests (add imports `java.util.Set`, `io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent`):

```java
    @Test
    void emitsSessionErrorForExpectedMcpServersThatAreNotConnected() throws Exception {
        String mcp = "{\"axiom\":{\"status\":\"connected\"},"
                + "\"broken\":{\"status\":\"failed\",\"error\":\"ENOENT\"},"
                + "\"user-global\":{\"status\":\"failed\",\"error\":\"ignored\"}}";
        try (FakeOpenCodeServer server = FakeOpenCodeServer.startWithMcp(mcp)) {
            List<SseEvent> events = new CopyOnWriteArrayList<>();
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    events::add,
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null,
                    Set.of("axiom", "broken", "missing"));

            driver.start();

            List<String> messages = events.stream()
                    .filter(e -> "session_error".equals(e.type()))
                    .filter(e -> "McpServerUnavailable".equals(e.data().path("name").asText()))
                    .map(e -> e.data().path("message").asText())
                    .toList();
            assertEquals(List.of(
                    "MCP server 'broken' is unavailable (failed): ENOENT",
                    "MCP server 'missing' was not loaded by OpenCode"), messages);
            assertEquals(AssistantSession.Status.RUNNING, driver.getStatus());

            driver.destroy();
        }
    }

    @Test
    void emitsNoMcpWarningsWhenAllExpectedServersConnected() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.startWithMcp("{\"axiom\":{\"status\":\"connected\"}}")) {
            List<SseEvent> events = new CopyOnWriteArrayList<>();
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    events::add,
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null,
                    Set.of("axiom"));

            driver.start();

            assertTrue(events.stream().noneMatch(e -> "McpServerUnavailable".equals(e.data().path("name").asText())));
            driver.destroy();
        }
    }
```

Note: `EventHandler` emits a `session.turn.completed` event on connect, so filter on
`name == McpServerUnavailable` as shown rather than asserting the total event count.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app test -Dtest=OpenCodeInteractiveSessionDriverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (no 9-arg constructor).

- [ ] **Step 3: Implement**

Add imports: `com.fasterxml.jackson.databind.node.JsonNodeFactory`, `com.fasterxml.jackson.databind.node.ObjectNode`,
`java.util.Map`, `java.util.TreeSet`. Add field `private final Set<String> expectedMcpServers;`.

Constructors:

```java
    /**
     * Creates an OpenCode interactive session driver without expected MCP servers.
     * (keep existing Javadoc params)
     */
    public OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                            CapabilityProbe capabilityProbe,
                                            OpenCodeEventNormalizer normalizer,
                                            Consumer<SseEvent> eventSink,
                                            Consumer<SseEvent> autoApprovalSink,
                                            String sessionTitle,
                                            String model,
                                            JsonNode tools) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                sessionTitle, model, tools, Set.of());
    }

    /**
     * Creates an OpenCode interactive session driver.
     *
     * @param serverProcess OpenCode session server handle
     * @param capabilityProbe OpenCode capability probe
     * @param normalizer event normalizer
     * @param eventSink sink for non-permission events
     * @param autoApprovalSink sink for permission_request events
     * @param sessionTitle title used when creating OpenCode sessions
     * @param model model in provider/model format
     * @param tools optional tools payload for prompt submissions
     * @param expectedMcpServers names of MCP servers configured for the session; a warning is emitted
     *                           for each one that OpenCode does not report as connected
     */
    public OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                            CapabilityProbe capabilityProbe,
                                            OpenCodeEventNormalizer normalizer,
                                            Consumer<SseEvent> eventSink,
                                            Consumer<SseEvent> autoApprovalSink,
                                            String sessionTitle,
                                            String model,
                                            JsonNode tools,
                                            Set<String> expectedMcpServers) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                OpenCodeAssistantClient::connectEvents, sessionTitle, model, tools, expectedMcpServers);
    }

    OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                     CapabilityProbe capabilityProbe,
                                     OpenCodeEventNormalizer normalizer,
                                     Consumer<SseEvent> eventSink,
                                     Consumer<SseEvent> autoApprovalSink,
                                     EventStreamConnector eventStreamConnector,
                                     String sessionTitle,
                                     String model,
                                     JsonNode tools) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                eventStreamConnector, sessionTitle, model, tools, Set.of());
    }

    OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                     CapabilityProbe capabilityProbe,
                                     OpenCodeEventNormalizer normalizer,
                                     Consumer<SseEvent> eventSink,
                                     Consumer<SseEvent> autoApprovalSink,
                                     EventStreamConnector eventStreamConnector,
                                     String sessionTitle,
                                     String model,
                                     JsonNode tools,
                                     Set<String> expectedMcpServers) {
        // existing assignments ...
        this.expectedMcpServers = expectedMcpServers != null ? Set.copyOf(expectedMcpServers) : Set.of();
    }
```

In `start()`, immediately after
`status.compareAndSet(AssistantSession.Status.STARTING, AssistantSession.Status.RUNNING);` add
`reportMcpServerStatus();`. Add the methods:

```java
    private void reportMcpServerStatus() {
        if (expectedMcpServers.isEmpty()) {
            return;
        }
        Map<String, OpenCodeAssistantClient.McpServerStatus> statuses;
        try {
            statuses = client.mcpStatus();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to query OpenCode MCP server status");
            emitMcpWarning("Unable to verify MCP server status: " + e.getMessage());
            return;
        }
        for (String name : new TreeSet<>(expectedMcpServers)) {
            OpenCodeAssistantClient.McpServerStatus serverStatus = statuses.get(name);
            if (serverStatus == null) {
                emitMcpWarning("MCP server '" + name + "' was not loaded by OpenCode");
            } else if (!serverStatus.connected()) {
                String message = "MCP server '" + name + "' is unavailable (" + serverStatus.status() + ")";
                if (serverStatus.error() != null && !serverStatus.error().isBlank()) {
                    message += ": " + serverStatus.error();
                }
                emitMcpWarning(message);
            }
        }
    }

    private void emitMcpWarning(String message) {
        LOG.warn(message);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("name", "McpServerUnavailable");
        data.put("message", message);
        eventSink.accept(new SseEvent("session_error", data));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl app test -Dtest='OpenCode*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all OpenCode tests PASS (including the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java
git commit -m "feat(assistant): warn when OpenCode session MCP servers are unavailable (#368)"
```

---

### Task 5: Wire MCP servers from the session manager through the factory

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java:46-121`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java:245-259`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java`

**Interfaces:**
- Consumes: `OpenCodeConfigWriter.writeConfig` (Task 1), 5-arg `OpenCodeSessionServerProcess` (Task 2),
  9-arg public `OpenCodeInteractiveSessionDriver` constructor (Task 4).
- Produces: `DriverRequest` gains a trailing component
  `Map<String, AssistantContextBuilder.McpServerConfig> mcpServers` (null normalized to `Map.of()`).

- [ ] **Step 1: Write the failing test**

Update the three existing `new InteractiveSessionDriverFactory.DriverRequest(...)` calls in
`InteractiveSessionDriverFactoryTest` to pass `Map.of()` as a new last argument (after `"Session"`). Then add
(imports: `io.apitomy.axiom.app.assistant.AssistantContextBuilder`, `org.junit.jupiter.api.io.TempDir`,
`java.nio.file.Files`, `static org.junit.jupiter.api.Assertions.assertTrue`,
`static org.junit.jupiter.api.Assertions.assertFalse`):

```java
    @Test
    void createDriverWritesOpenCodeConfigAndPointsServerAtIt(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory factory = defaultFactory();

        InteractiveSessionDriver driver = factory.createDriver(new InteractiveSessionDriverFactory.DriverRequest(
                "opencode", "axiom-config-assistant", sessionDir, sessionDir.resolve("work"),
                List.of(), Map.of(), null, null, event -> {
                }, event -> {
                }, "github-copilot/claude-sonnet-5", null, "Session",
                Map.of("axiom", AssistantContextBuilder.McpServerConfig.stdio("node", List.of("server.js"), Map.of()))));

        Path configFile = sessionDir.resolve("opencode.json");
        assertTrue(Files.exists(configFile));
        assertTrue(Files.readString(configFile).contains("\"axiom\""));

        Object process = extractProcess(driver);
        @SuppressWarnings("unchecked")
        Map<String, String> environment = (Map<String, String>) getField(process, "environment");
        assertEquals(configFile.toString(), environment.get("OPENCODE_CONFIG"));

        @SuppressWarnings("unchecked")
        java.util.Set<String> expected = (java.util.Set<String>) getField(driver, "expectedMcpServers");
        assertEquals(java.util.Set.of("axiom"), expected);
    }

    @Test
    void createDriverSkipsOpenCodeConfigWhenNoMcpServers(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "general-assistant", sessionDir, sessionDir, List.of(), Map.of(),
                        null, null, event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of()));

        assertFalse(Files.exists(sessionDir.resolve("opencode.json")));
        Object process = extractProcess(driver);
        @SuppressWarnings("unchecked")
        Map<String, String> environment = (Map<String, String>) getField(process, "environment");
        assertFalse(environment.containsKey("OPENCODE_CONFIG"));
    }

    private static InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory defaultFactory()
            throws Exception {
        InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory factory =
                new InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory();
        setField(factory, "assistantOpenCodeExecutable", Optional.empty());
        setField(factory, "openCodeExecutable", "opencode");
        setField(factory, "assistantOpenCodeStartupTimeoutSeconds", Optional.empty());
        setField(factory, "legacyAssistantOpenCodeServerStartupTimeoutSeconds", Optional.empty());
        setField(factory, "openCodeServerStartupTimeoutSeconds", 30);
        setField(factory, "assistantOpenCodeServerPort", Optional.empty());
        setField(factory, "openCodeServerHostname", "127.0.0.1");
        setField(factory, "openCodeServerPort", 0);
        return factory;
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app test -Dtest=InteractiveSessionDriverFactoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`DriverRequest` has 13 components, not 14).

- [ ] **Step 3: Implement**

In `InteractiveSessionDriverFactory`: add imports `io.apitomy.axiom.app.assistant.AssistantContextBuilder`,
`io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeConfigWriter`, `java.util.LinkedHashMap`.
Extend the record (add `@param mcpServers resolved MCP servers for the session, keyed by name` to the Javadoc):

```java
    record DriverRequest(String engineType,
                         String templateId,
                         Path sessionDirectory,
                         Path workingDirectory,
                         List<String> command,
                         Map<String, String> environment,
                         Long projectId,
                         String projectName,
                         Consumer<AssistantEventParser.SseEvent> eventSink,
                         Consumer<AssistantEventParser.SseEvent> autoApprovalSink,
                         String model,
                         com.fasterxml.jackson.databind.JsonNode tools,
                         String sessionTitle,
                         Map<String, AssistantContextBuilder.McpServerConfig> mcpServers) {

        /**
         * Normalizes a null MCP server map to an empty map.
         */
        public DriverRequest {
            mcpServers = mcpServers != null ? mcpServers : Map.of();
        }
    }
```

Replace the start of the opencode branch in `createDriver`:

```java
            if ("opencode".equalsIgnoreCase(engineType)) {
                Map<String, String> serverEnvironment = new LinkedHashMap<>();
                Path openCodeConfig = OpenCodeConfigWriter.writeConfig(
                        request.sessionDirectory(), request.mcpServers());
                if (openCodeConfig != null) {
                    serverEnvironment.put("OPENCODE_CONFIG", openCodeConfig.toString());
                }
                OpenCodeSessionServerProcess openCodeSessionServerProcess =
                        new OpenCodeSessionServerProcess(resolveOpenCodeExecutable(),
                                openCodeServerHostname,
                                resolveOpenCodeServerPort(),
                                resolveOpenCodeStartupTimeoutSeconds(),
                                serverEnvironment);
```

and pass `request.mcpServers().keySet()` as the new last argument to `new OpenCodeInteractiveSessionDriver(...)`.

In `AssistantSessionManager.java` (around line 258), add `mcpConfigs` as the last `DriverRequest` argument:

```java
                            template.model(),
                            openCodeTools,
                            sessionName,
                            mcpConfigs));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl app test -Dtest='InteractiveSessionDriverFactoryTest,OpenCode*Test,AssistantSession*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java
git commit -m "fix(assistant): expose session MCP servers to OpenCode sessions (#368)"
```

---

### Task 6: Real-opencode integration test, docs, and end-to-end verification

**Files:**
- Modify: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java`
- Modify: `docs/user-guide/ai-assistant.md` (near line 17-20, the engine/agent type description)

**Interfaces:**
- Consumes: everything from Tasks 1-3.

- [ ] **Step 1: Add an integration test that runs against real opencode (skipped if not installed)**

Add imports `io.apitomy.axiom.app.assistant.AssistantContextBuilder.McpServerConfig`,
`org.junit.jupiter.api.io.TempDir`, `java.nio.file.Path`.

```java
    @Test
    void realOpenCodeLoadsMcpServersFromGeneratedConfig(@TempDir Path sessionDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        Path config = OpenCodeConfigWriter.writeConfig(sessionDir, Map.of(
                "axiom-it-bogus", McpServerConfig.stdio("/nonexistent/axiom-it-cmd", List.of(), Map.of())));
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of("OPENCODE_CONFIG", config.toString()));
        try {
            process.start();
            Map<String, OpenCodeAssistantClient.McpServerStatus> statuses =
                    new OpenCodeAssistantClient(process.baseUrl()).mcpStatus();

            assertTrue(statuses.containsKey("axiom-it-bogus"), "status map: " + statuses);
            assertEquals("failed", statuses.get("axiom-it-bogus").status());
        } finally {
            process.stop();
        }
    }
```

- [ ] **Step 2: Run it**

Run: `mvn -q -pl app test -Dtest=OpenCodeSessionServerProcessTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS where opencode is installed (verified manually with opencode 1.18.33 that this server reports
`failed` with an `ENOENT` error); skipped otherwise.

- [ ] **Step 3: Update the user guide**

In `docs/user-guide/ai-assistant.md`, after the paragraph describing the agent type (lines ~17-21), add
(wrap at 110 columns):

```markdown
    MCP servers configured on a template are available to both Claude Code and OpenCode sessions. For
    OpenCode, Axiom writes them to an `opencode.json` file in the session directory and starts the
    session's `opencode serve` process with `OPENCODE_CONFIG` pointing at it. MCP servers from your global
    OpenCode configuration are also loaded. If a configured MCP server fails to connect, the session shows a
    warning message with the error reported by OpenCode.
```

(Match the indentation of the surrounding list item; if the paragraph is not inside a list item, remove the
leading four spaces.)

- [ ] **Step 4: Full module test run**

Run: `mvn -q -pl app test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Manual end-to-end verification**

1. Build and run Axiom (`./dev.sh` or per `docs/developer-guide/building-axiom.md`).
2. Edit the "Configuration Assistant" session template, set **Agent Type** to `opencode` and a valid
   `provider/model` (e.g. `github-copilot/claude-sonnet-4.5`).
3. Start a session. Confirm `~/.axiom/assistant/sessions/<id>/opencode.json` exists with an `axiom` entry, and
   `ps -ef | grep "opencode serve"` shows the process. `curl http://127.0.0.1:<port>/mcp` shows
   `"axiom":{"status":"connected"}`.
4. Ask: "List the tools currently configured in Axiom." Expected: the model calls the `axiom_axiom_list_tools`
   MCP tool and answers from real data. (Tool-call rendering in the UI may still be missing until #372; check
   the answer content and the `opencode serve` debug log.)
5. Temporarily add a template MCP server pointing at a non-existent command; start a session and confirm a
   system message "MCP server '<name>' is unavailable (failed): ..." appears.

Known limitation to note in the PR (not fixed here): the template's allowed-tools list is still sent in
Claude format (#371), and the working directory/system prompt are still not applied (#369, #370). The
Configuration Assistant will not be fully functional on opencode until those land.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java \
        docs/user-guide/ai-assistant.md
git commit -m "test(assistant): verify OpenCode loads session MCP config; document behavior (#368)"
```

---

## Self-Review Notes

- Issue #368 requirements → tasks: carry MCP configs in `DriverRequest` (Task 5); register servers via config
  file + `OPENCODE_CONFIG` (Tasks 1, 2, 5); translate stdio → `local`, http → `remote` (Task 1); check
  `GET /mcp` and surface `failed`/`needs_auth` (Tasks 3, 4); acceptance check with the Configuration Assistant
  (Task 6).
- Chose the config-file approach over runtime `POST /mcp` because servers are loaded before the first prompt
  (no race), the file is inspectable for debugging (mirrors Claude's `mcp-config.json`), and it was verified
  against real opencode. The file is deleted with the session directory on destroy and is written `0600`.
- Names/types used across tasks: `OpenCodeConfigWriter.writeConfig/buildConfig/CONFIG_FILE_NAME`,
  `OpenCodeSessionServerProcess(…, Map<String,String> environment)`, `createProcessBuilder(int)`,
  `OpenCodeAssistantClient.mcpStatus()` / `McpServerStatus(status, error).connected()`, driver field
  `expectedMcpServers`, `DriverRequest.mcpServers`.
