package io.apitomy.axiom.agents.opencode;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link OpenCodeServerManager} does not silently reuse an OpenCode server it did not start.
 */
class OpenCodeServerManagerForeignServerTest {

    @Test
    void choosesAnotherPortWhenForeignServerOccupiesConfiguredPort() throws Exception {
        HttpServer foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        foreign.createContext("/global/health", exchange -> {
            byte[] bytes = "{\"healthy\":true,\"version\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        foreign.start();
        try {
            int foreignPort = foreign.getAddress().getPort();
            OpenCodeServerManager manager = new OpenCodeServerManager("127.0.0.1", foreignPort);
            assertTrue(manager.isForeignServerRunning(foreignPort));
            int chosen = manager.selectLaunchPort();
            assertNotEquals(foreignPort, chosen);
            assertTrue(chosen > 0);
            assertFalse(manager.isAxiomLaunched());
        } finally {
            foreign.stop(0);
        }
    }

    @Test
    void keepsConfiguredPortWhenFree() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        OpenCodeServerManager manager = new OpenCodeServerManager("127.0.0.1", freePort);
        assertFalse(manager.isForeignServerRunning(freePort));
        assertEquals(freePort, manager.selectLaunchPort());
    }
}
