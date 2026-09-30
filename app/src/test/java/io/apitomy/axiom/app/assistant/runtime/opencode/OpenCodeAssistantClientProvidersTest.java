package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenCodeAssistantClientProvidersTest {

    // Shape captured from opencode 1.18.33, trimmed to two models per provider
    private static final String PROVIDERS_RESPONSE = "{\"providers\":["
            + "{\"id\":\"github-copilot\",\"models\":{\"gpt-5.4\":{\"id\":\"gpt-5.4\"},"
            + "\"claude-sonnet-5\":{\"id\":\"claude-sonnet-5\"}}},"
            + "{\"id\":\"opencode\",\"models\":{\"big-pickle\":{},\"gpt-5-nano\":{}}}],"
            + "\"default\":{\"github-copilot\":\"gpt-5.6-terra\",\"opencode\":\"big-pickle\"}}";

    @Test
    void parsesProviderCatalog() throws Exception {
        HttpServer server = startServer(200, PROVIDERS_RESPONSE);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));

            OpenCodeAssistantClient.ProviderCatalog catalog = client.providerCatalog();

            assertEquals(List.of("github-copilot", "opencode"), List.copyOf(catalog.models().keySet()));
            assertEquals(Set.of("gpt-5.4", "claude-sonnet-5"), catalog.models().get("github-copilot"));
            assertEquals(Set.of("big-pickle", "gpt-5-nano"), catalog.models().get("opencode"));
            assertEquals("gpt-5.6-terra", catalog.defaults().get("github-copilot"));
            assertEquals("big-pickle", catalog.defaults().get("opencode"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesModelsGivenAsArray() throws Exception {
        HttpServer server = startServer(200,
                "{\"providers\":[{\"id\":\"p\",\"models\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}]}");
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));

            OpenCodeAssistantClient.ProviderCatalog catalog = client.providerCatalog();

            assertEquals(Set.of("m1", "m2"), catalog.models().get("p"));
            assertEquals(0, catalog.defaults().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsOnHttpError() throws Exception {
        HttpServer server = startServer(500, "{}");
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertThrows(IllegalStateException.class, client::providerCatalog);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsWhenProvidersIsNotAnArray() throws Exception {
        HttpServer server = startServer(200, "{\"providers\":{}}");
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertThrows(IllegalStateException.class, client::providerCatalog);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/config/providers", exchange -> {
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
