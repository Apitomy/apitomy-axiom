package io.apitomy.axiom.agents.opencode;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link OpenCodeClient} scopes session requests to a working directory and validates models.
 */
class OpenCodeClientDirectoryTest {

    private HttpServer server;
    private final List<String> requestUris = new CopyOnWriteArrayList<>();
    private int status = 200;
    private String messageBody = "{\"info\":{},\"parts\":[]}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUris.add(exchange.getRequestURI().toString());
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/message") ? messageBody : "{\"id\":\"ses_1\"}";
            int code = path.endsWith("/message") ? status : 200;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenCodeClient client() {
        return new OpenCodeClient("127.0.0.1", server.getAddress().getPort());
    }

    private static String directoryParam(String uri) {
        String query = uri.substring(uri.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            if (pair.startsWith("directory=")) {
                return URLDecoder.decode(pair.substring("directory=".length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void createSessionPassesDirectory() throws Exception {
        String id = client().createSession("t", "/tmp/my work/dir", 5);
        assertEquals("ses_1", id);
        assertEquals("/session", requestUris.get(0).split("\\?")[0]);
        assertEquals("/tmp/my work/dir", directoryParam(requestUris.get(0)));
    }

    @Test
    void sendPromptAndAbortPassDirectory() throws Exception {
        OpenCodeClient client = client();
        client.sendPrompt("ses_1", "/ws", "hi", "anthropic/claude", null, null, 5);
        client.abortSession("ses_1", "/ws");
        assertEquals("/session/ses_1/message", requestUris.get(0).split("\\?")[0]);
        assertEquals("/ws", directoryParam(requestUris.get(0)));
        assertEquals("/session/ses_1/abort", requestUris.get(1).split("\\?")[0]);
        assertEquals("/ws", directoryParam(requestUris.get(1)));
    }

    @Test
    void noDirectoryOmitsQuery() throws Exception {
        client().createSession("t", null, 5);
        assertEquals("/session", requestUris.get(0));
    }

    @Test
    void modelWithoutProviderIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client().sendPrompt("ses_1", "/ws", "hi", "claude-sonnet", null, null, 5));
        assertTrue(ex.getMessage().contains("provider/model"), ex.getMessage());
        assertTrue(requestUris.isEmpty());
    }

    @Test
    void promptErrorIncludesServerMessage() {
        status = 500;
        messageBody = "{\"name\":\"UnknownError\",\"data\":{\"message\":\"ENOENT realpath /tmp/gone\",\"ref\":\"err_42\"}}";
        IOException ex = assertThrows(IOException.class,
                () -> client().sendPrompt("ses_1", "/ws", "hi", null, null, null, 5));
        assertTrue(ex.getMessage().contains("ENOENT realpath /tmp/gone"), ex.getMessage());
        assertTrue(ex.getMessage().contains("err_42"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }
}
