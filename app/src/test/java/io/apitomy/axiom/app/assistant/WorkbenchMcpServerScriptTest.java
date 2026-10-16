package io.apitomy.axiom.app.assistant;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the workbench MCP server's Node unit tests ({@code node --test}).
 */
class WorkbenchMcpServerScriptTest {

    @Test
    void nodeHelperTestsPass() throws IOException, InterruptedException {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed");
        Path test = Path.of("src/test/node/workbench-mcp.test.js").toAbsolutePath();
        Process process = new ProcessBuilder("node", "--test", test.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(60, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue(), output);
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
