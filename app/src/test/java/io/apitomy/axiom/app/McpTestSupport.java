package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test helpers for generated MCP server configurations: unwrapping the environment launcher, starting a server
 * the way an agent CLI does, and talking to it over stdio JSON-RPC.
 */
public final class McpTestSupport {

    private McpTestSupport() {
    }

    /**
     * Returns the full configured command line of a server entry ({@code command} followed by {@code args}).
     *
     * @param server the server entry ({@code command}, {@code args})
     * @return the command line
     */
    public static List<String> configuredCommand(JsonNode server) {
        List<String> cmd = new ArrayList<>();
        cmd.add(server.path("command").asText());
        server.path("args").forEach(a -> cmd.add(a.asText()));
        return cmd;
    }

    /**
     * Returns whether a server entry is started through the environment launcher.
     *
     * @param server the server entry
     * @return true if launched through {@code env-launcher.js}
     */
    public static boolean isLaunched(JsonNode server) {
        JsonNode args = server.path("args");
        return "node".equals(server.path("command").asText()) && args.size() >= 4
                && args.get(0).asText().endsWith("env-launcher.js") && "--keep".equals(args.get(1).asText());
    }

    /**
     * Returns the real command line behind the environment launcher (everything after {@code --}), or the
     * configured command line if the entry is not launched.
     *
     * @param server the server entry
     * @return the real command line
     */
    public static List<String> realCommand(JsonNode server) {
        List<String> cmd = configuredCommand(server);
        if (!isLaunched(server)) {
            return cmd;
        }
        int separator = cmd.indexOf("--");
        return new ArrayList<>(cmd.subList(separator + 1, cmd.size()));
    }

    /**
     * Returns the variable names the launcher keeps for a server entry.
     *
     * @param server the server entry
     * @return the kept names (empty if not launched)
     */
    public static List<String> keptNames(JsonNode server) {
        if (!isLaunched(server)) {
            return List.of();
        }
        String csv = server.path("args").get(2).asText();
        return csv.isEmpty() ? List.of() : List.of(csv.split(","));
    }

    /**
     * Starts a server entry the way an agent CLI does: with the CLI's own environment merged with the entry's
     * configured {@code env}.
     *
     * @param command       the configured command line
     * @param configuredEnv the entry's configured {@code env}
     * @param cliEnv        the CLI's environment (added on top of this JVM's environment)
     * @return the started process
     * @throws java.io.IOException if it cannot be started
     */
    public static Process startLikeCli(List<String> command, Map<String, String> configuredEnv,
                                       Map<String, String> cliEnv) throws java.io.IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(cliEnv);
        pb.environment().putAll(configuredEnv);
        return pb.start();
    }

    /**
     * Reads a server entry's configured {@code env} object.
     *
     * @param server the server entry ({@code env} or OpenCode's {@code environment})
     * @return the variables
     */
    public static Map<String, String> configuredEnv(JsonNode server) {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        JsonNode node = server.has("env") ? server.get("env") : server.path("environment");
        node.fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));
        return env;
    }

    /**
     * Initializes an MCP session, calls one tool and returns the text of its first content item.
     *
     * @param process  the started MCP server
     * @param mapper   the object mapper
     * @param toolName the tool to call
     * @param args     the tool arguments
     * @return the result text
     * @throws Exception on protocol errors or timeouts
     */
    public static String callTool(Process process, ObjectMapper mapper, String toolName, Map<String, Object> args)
            throws Exception {
        OutputStream stdin = process.getOutputStream();
        BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        send(stdin, mapper, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "axiom-test", "version", "1.0.0"))));
        read(stdout, mapper, 15000);
        send(stdin, mapper, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        send(stdin, mapper, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", args)));
        JsonNode response = read(stdout, mapper, 15000);
        return response.path("result").path("content").path(0).path("text").asText();
    }

    private static void send(OutputStream out, ObjectMapper mapper, Map<String, Object> message) throws Exception {
        out.write(mapper.writeValueAsBytes(message));
        out.write('\n');
        out.flush();
    }

    private static JsonNode read(BufferedReader reader, ObjectMapper mapper, long timeoutMs) throws Exception {
        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> {
            try {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new IllegalStateException("Server closed stdout unexpectedly");
                    }
                    JsonNode node = mapper.readTree(line);
                    if (node.has("id")) {
                        return node;
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
