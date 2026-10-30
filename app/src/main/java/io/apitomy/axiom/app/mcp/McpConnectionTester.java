package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.agents.spi.process.BoundedProcessRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects to an MCP server, performs the {@code initialize} handshake and lists its tools. Supports the
 * stdio, streamable HTTP and (legacy) SSE transports. Used to test a server's configuration and to discover
 * its tool names; it never calls tools.
 */
@ApplicationScoped
public class McpConnectionTester {

    private static final Logger LOG = Logger.getLogger(McpConnectionTester.class);

    /** Protocol version offered in {@code initialize}; servers may answer with an older one. */
    static final String PROTOCOL_VERSION = "2025-06-18";

    /** Maximum number of {@code tools/list} pages to fetch. */
    private static final int MAX_PAGES = 50;

    /** Maximum number of captured log characters (stderr for stdio servers). */
    private static final int MAX_LOG_CHARS = 8000;

    private final ObjectMapper objectMapper;

    /**
     * CDI constructor.
     *
     * @param objectMapper the object mapper
     */
    @Inject
    public McpConnectionTester(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A tool reported by a server.
     *
     * @param name the tool name
     * @param description the tool description, or null
     */
    public record ToolInfo(String name, String description) {
    }

    /**
     * Outcome of a connection test.
     *
     * @param success whether the handshake and tool listing succeeded
     * @param serverName the server's reported name, or null
     * @param serverVersion the server's reported version, or null
     * @param protocolVersion the negotiated protocol version, or null
     * @param tools the server's tools (empty on failure)
     * @param error a description of the failure, or null
     * @param log captured server output (stderr for stdio servers), possibly truncated
     * @param durationMs how long the test took
     */
    public record Result(boolean success, String serverName, String serverVersion, String protocolVersion,
                         List<ToolInfo> tools, String error, String log, long durationMs) {
    }

    /**
     * Tests a server.
     *
     * @param def the resolved server definition
     * @param timeout the overall time limit
     * @return the result; never throws
     */
    public Result test(McpServerDefinition def, Duration timeout) {
        Instant start = Instant.now();
        Instant deadline = start.plus(timeout);
        Transport transport = null;
        String serverName = null;
        String serverVersion = null;
        String protocolVersion = null;
        try {
            transport = open(def, deadline);
            ObjectNode initParams = objectMapper.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
            initParams.putObject("capabilities");
            initParams.putObject("clientInfo").put("name", "apitomy-axiom").put("version", "3");
            JsonNode init = transport.request("initialize", initParams, deadline);
            serverName = textOrNull(init.path("serverInfo"), "name");
            serverVersion = textOrNull(init.path("serverInfo"), "version");
            protocolVersion = textOrNull(init, "protocolVersion");
            transport.setProtocolVersion(protocolVersion);
            transport.notify("notifications/initialized", deadline);

            List<ToolInfo> tools = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ObjectNode params = objectMapper.createObjectNode();
                if (cursor != null) {
                    params.put("cursor", cursor);
                }
                JsonNode result = transport.request("tools/list", params, deadline);
                for (JsonNode tool : result.path("tools")) {
                    tools.add(new ToolInfo(tool.path("name").asText(), textOrNull(tool, "description")));
                }
                cursor = textOrNull(result, "nextCursor");
                if (cursor == null || cursor.isEmpty()) {
                    break;
                }
            }
            return new Result(true, serverName, serverVersion, protocolVersion, tools, null,
                    transport.log(), elapsed(start));
        } catch (Exception e) {
            String error = transport != null ? transport.describeFailure(e) : message(e);
            String log = transport != null ? transport.log() : "";
            LOG.debugf(e, "MCP connection test failed: %s", error);
            return new Result(false, serverName, serverVersion, protocolVersion, List.of(), error, log,
                    elapsed(start));
        } finally {
            if (transport != null) {
                transport.close();
            }
        }
    }

    private Transport open(McpServerDefinition def, Instant deadline) throws Exception {
        if (!def.isHttpTransport()) {
            return new StdioTransport(def);
        }
        if ("sse".equals(def.type())) {
            return new SseTransport(def, deadline);
        }
        return new StreamableHttpTransport(def);
    }

    private static long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static Duration remaining(Instant deadline) throws McpException {
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isNegative() || left.isZero()) {
            throw new McpException("Timed out waiting for the server");
        }
        return left;
    }

    /** A protocol or transport failure with a user-facing message. */
    static class McpException extends Exception {
        McpException(String message) {
            super(message);
        }
    }

    /** Base class for transports: JSON-RPC request/response matching. */
    private abstract class Transport {
        private final AtomicInteger nextId = new AtomicInteger(1);
        protected String protocolVersion;

        /** Sends a request and waits for its result. */
        JsonNode request(String method, JsonNode params, Instant deadline) throws Exception {
            int id = nextId.getAndIncrement();
            ObjectNode msg = objectMapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            msg.set("params", params);
            JsonNode response = exchange(msg, id, deadline);
            if (response.has("error")) {
                throw new McpException(method + " failed: " + response.path("error").path("message").asText(
                        response.path("error").toString()));
            }
            return response.path("result");
        }

        /** Sends a notification. */
        void notify(String method, Instant deadline) throws Exception {
            ObjectNode msg = objectMapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
            exchange(msg, null, deadline);
        }

        void setProtocolVersion(String version) {
            this.protocolVersion = version;
        }

        /** Sends a message; for requests (id != null) returns the matching response. */
        abstract JsonNode exchange(ObjectNode message, Integer id, Instant deadline) throws Exception;

        /** Captured server output. */
        String log() {
            return "";
        }

        /** Describes a failure, adding transport details. */
        String describeFailure(Exception e) {
            return message(e);
        }

        abstract void close();

        /** Builds a reply to a server-initiated request: {@code ping} succeeds, others are unsupported. */
        ObjectNode replyTo(JsonNode serverRequest) {
            ObjectNode reply = objectMapper.createObjectNode().put("jsonrpc", "2.0");
            reply.set("id", serverRequest.get("id"));
            if ("ping".equals(serverRequest.path("method").asText())) {
                reply.putObject("result");
            } else {
                reply.putObject("error").put("code", -32601).put("message", "Method not supported");
            }
            return reply;
        }

        boolean isResponseTo(JsonNode message, Integer id) {
            return id != null && message.has("id") && !message.has("method") && message.path("id").asInt(-1) == id;
        }
    }

    /** Newline-delimited JSON-RPC over a child process's stdin/stdout. */
    private final class StdioTransport extends Transport {
        private final Process process;
        private final OutputStream stdin;
        private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
        private final StringBuilder stderr = new StringBuilder();
        private final String command;

        StdioTransport(McpServerDefinition def) throws McpException {
            this.command = def.command();
            List<String> cmd = new ArrayList<>();
            cmd.add(def.command());
            cmd.addAll(def.args());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(def.env());
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new McpException("Could not start '" + def.command() + "': " + message(e));
            }
            stdin = process.getOutputStream();
            Thread.ofVirtual().start(() -> readStdout(process.getInputStream()));
            Thread.ofVirtual().start(() -> readStderr(process.getErrorStream()));
        }

        private void readStdout(InputStream in) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        incoming.add(objectMapper.readTree(line));
                    } catch (IOException e) {
                        appendLog(line);
                    }
                }
            } catch (IOException e) {
                // process ended
            }
        }

        private void readStderr(InputStream in) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            } catch (IOException e) {
                // process ended
            }
        }

        private synchronized void appendLog(String line) {
            if (stderr.length() < MAX_LOG_CHARS) {
                stderr.append(line, 0, Math.min(line.length(), MAX_LOG_CHARS - stderr.length())).append('\n');
            }
        }

        private void write(JsonNode message) throws IOException {
            synchronized (stdin) {
                stdin.write((message.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        }

        @Override
        JsonNode exchange(ObjectNode message, Integer id, Instant deadline) throws Exception {
            try {
                write(message);
            } catch (IOException e) {
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    throw new McpException("Server exited with code " + process.exitValue());
                }
                throw new McpException("Could not write to the server: " + message(e));
            }
            if (id == null) {
                return null;
            }
            while (true) {
                JsonNode next = incoming.poll(Math.min(remaining(deadline).toMillis(), 200), TimeUnit.MILLISECONDS);
                if (next == null) {
                    if (!process.isAlive() && incoming.isEmpty()) {
                        throw new McpException("Server exited with code " + process.exitValue());
                    }
                    continue;
                }
                if (isResponseTo(next, id)) {
                    return next;
                }
                if (next.has("method") && next.has("id")) {
                    write(replyTo(next));
                }
            }
        }

        @Override
        synchronized String log() {
            return stderr.toString();
        }

        @Override
        String describeFailure(Exception e) {
            String msg = message(e);
            if (msg.startsWith("Server exited")) {
                // Give the stderr reader a moment to capture the final lines.
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            return msg + " (command: " + command + ")";
        }

        @Override
        void close() {
            BoundedProcessRunner.killTree(process);
        }
    }

    /** Streamable HTTP: each message is a POST; responses are JSON or an SSE stream. */
    private final class StreamableHttpTransport extends Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final URI uri;
        private final Map<String, String> headers;
        private String sessionId;

        StreamableHttpTransport(McpServerDefinition def) {
            this.uri = URI.create(def.url());
            this.headers = def.headers();
        }

        @Override
        JsonNode exchange(ObjectNode message, Integer id, Instant deadline) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(remaining(deadline))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(message.toString()));
            headers.forEach(builder::header);
            if (sessionId != null) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            if (protocolVersion != null) {
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new McpException("Timed out waiting for the server");
            } catch (IOException e) {
                throw new McpException("Could not connect to " + uri + ": " + message(e));
            }
            try (InputStream body = response.body()) {
                response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
                int status = response.statusCode();
                if (status >= 400) {
                    String text = new String(body.readNBytes(500), StandardCharsets.UTF_8);
                    throw new McpException("HTTP " + status + " from " + uri + (text.isBlank() ? "" : ": " + text));
                }
                if (id == null) {
                    return null;
                }
                String type = response.headers().firstValue("Content-Type").orElse("");
                if (type.startsWith("text/event-stream")) {
                    SseReader reader = new SseReader(body);
                    while (true) {
                        Optional<SseEvent> event = reader.next();
                        if (event.isEmpty()) {
                            throw new McpException("The server closed the stream without a response");
                        }
                        JsonNode msg = objectMapper.readTree(event.get().data());
                        if (isResponseTo(msg, id)) {
                            return msg;
                        }
                    }
                }
                return objectMapper.readTree(body);
            }
        }

        @Override
        void close() {
            client.close();
        }
    }

    /** Legacy HTTP+SSE: a GET event stream for responses, POSTs to an endpoint announced on it. */
    private final class SseTransport extends Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final Map<String, String> headers;
        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final InputStream stream;
        private final URI endpoint;

        SseTransport(McpServerDefinition def, Instant deadline) throws Exception {
            this.headers = def.headers();
            URI uri = URI.create(def.url());
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream").GET();
            headers.forEach(builder::header);
            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new McpException("Could not connect to " + uri + ": " + message(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException("Interrupted");
            }
            if (response.statusCode() >= 400) {
                response.body().close();
                throw new McpException("HTTP " + response.statusCode() + " from " + uri);
            }
            stream = response.body();
            Thread.ofVirtual().start(() -> {
                SseReader reader = new SseReader(stream);
                try {
                    Optional<SseEvent> event;
                    while ((event = reader.next()).isPresent()) {
                        events.add(event.get());
                    }
                } catch (IOException e) {
                    // stream closed
                }
                events.add(Boolean.FALSE);
            });
            SseEvent first = nextEvent(deadline);
            if (!"endpoint".equals(first.event())) {
                throw new McpException("Expected an 'endpoint' event from the SSE server, got '" + first.event() + "'");
            }
            endpoint = uri.resolve(first.data().trim());
        }

        private SseEvent nextEvent(Instant deadline) throws Exception {
            Object next = events.poll(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS);
            if (next == null) {
                throw new McpException("Timed out waiting for the server");
            }
            if (next instanceof SseEvent e) {
                return e;
            }
            throw new McpException("The server closed the event stream");
        }

        @Override
        JsonNode exchange(ObjectNode message, Integer id, Instant deadline) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(remaining(deadline))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(message.toString()));
            headers.forEach(builder::header);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new McpException("HTTP " + response.statusCode() + " from " + endpoint);
            }
            if (id == null) {
                return null;
            }
            while (true) {
                SseEvent event = nextEvent(deadline);
                if (!"message".equals(event.event())) {
                    continue;
                }
                JsonNode msg = objectMapper.readTree(event.data());
                if (isResponseTo(msg, id)) {
                    return msg;
                }
            }
        }

        @Override
        void close() {
            try {
                stream.close();
            } catch (IOException e) {
                // ignore
            }
            client.close();
        }
    }

    /**
     * A server-sent event.
     *
     * @param event the event type ("message" if unspecified)
     * @param data the data lines joined with newlines
     */
    record SseEvent(String event, String data) {
    }

    /** Minimal {@code text/event-stream} parser. */
    static final class SseReader {
        private final BufferedReader reader;

        SseReader(InputStream in) {
            this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        Optional<SseEvent> next() throws IOException {
            String event = null;
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) {
                        return Optional.of(new SseEvent(event != null ? event : "message", data.toString()));
                    }
                    event = null;
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if ("event".equals(field)) {
                    event = value;
                } else if ("data".equals(field)) {
                    data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                }
            }
            return data != null ? Optional.of(new SseEvent(event != null ? event : "message", data.toString()))
                    : Optional.empty();
        }
    }
}
