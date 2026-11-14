package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.process.BoundedProcessRunner;
import io.apitomy.axiom.core.config.AxiomHome;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Installs bundled Node.js MCP servers under the Axiom home (copy template files, then {@code npm install}). */
@ApplicationScoped
public class McpServerInstaller {

    private static final Logger LOG = Logger.getLogger(McpServerInstaller.class);

    private final Map<String, Path> installedMcpServers = new ConcurrentHashMap<>();

    /**
     * Ensures a built-in Node.js MCP server is installed at {@code ${axiom.home}/<dirName>/}: copies its files
     * from the classpath and runs {@code npm install} once per process.
     *
     * @param dirName the install directory name under the Axiom home
     * @param resourceDir the classpath directory holding the server files (with trailing slash)
     * @param files the file names to copy from {@code resourceDir}
     * @return the install directory
     * @throws IOException if the server cannot be installed
     */
    public Path ensureInstalled(String dirName, String resourceDir, String... files) throws IOException {
        Path installed = installedMcpServers.get(dirName);
        if (installed != null && Files.exists(installed.resolve("node_modules"))) {
            return installed;
        }

        synchronized (this) {
            installed = installedMcpServers.get(dirName);
            if (installed != null && Files.exists(installed.resolve("node_modules"))) {
                return installed;
            }

            Path serverDir = AxiomHome.path().resolve(dirName);
            Files.createDirectories(serverDir);

            for (String fileName : files) {
                String resourcePath = resourceDir + fileName;
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new IOException("Template resource not found: " + resourcePath);
                    }
                    Files.copy(is, serverDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            LOG.infof("Copied MCP server template %s to %s", resourceDir, serverDir);

            LOG.infof("Running npm install for MCP server %s...", dirName);
            ProcessBuilder pb = new ProcessBuilder("npm", "install", "--production")
                    .directory(serverDir.toFile())
                    .redirectErrorStream(true);
            BoundedProcessRunner.Result result;
            try {
                result = BoundedProcessRunner.run(pb, McpConfigGenerator.NPM_INSTALL_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("npm install interrupted", e);
            }
            String output = result.stdout();
            if (result.timedOut()) {
                throw new IOException("npm install timed out after " + McpConfigGenerator.NPM_INSTALL_TIMEOUT.toMinutes()
                        + " minutes: " + output);
            }
            int exitCode = result.exitCode();

            if (exitCode != 0) {
                throw new IOException("npm install failed (exit code " + exitCode + "): " + output);
            }
            LOG.infof("npm install completed for MCP server in %s", serverDir);

            installedMcpServers.put(dirName, serverDir);
            return serverDir;
        }
    }
}
