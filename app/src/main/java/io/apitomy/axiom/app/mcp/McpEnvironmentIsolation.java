package io.apitomy.axiom.app.mcp;

import io.apitomy.axiom.core.config.AxiomHome;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps a calling agent's environment away from the stdio MCP servers it uses (#617).
 *
 * <p>Agent CLIs start stdio MCP servers with their own process environment merged with the server's configured
 * variables. Configuring a server's {@code env} is therefore not enough: the server would still inherit every
 * variable of the agent, including its secrets. This class rewrites each stdio definition so that it is started
 * through a small Node launcher ({@code env-launcher.js}) that clears the inherited environment and keeps only
 * the configured passthrough variables plus the server's own declared variables. Only variable names are put
 * on the command line, never values.</p>
 */
@ApplicationScoped
public class McpEnvironmentIsolation {

    /** The default passthrough allowlist. */
    public static final String DEFAULT_PASSTHROUGH = "PATH,HOME,TMPDIR,LANG,NODE_EXTRA_CA_CERTS";

    /** Prefix of the variables Axiom itself provides to tools (trace, subject, API URL and so on). */
    public static final String AXIOM_PREFIX = "AXIOM_";

    private static final String LAUNCHER_DIR_NAME = "mcp-launcher";
    private static final String LAUNCHER_FILE = "env-launcher.js";
    private static final String LAUNCHER_RESOURCE = "templates/mcp-launcher/" + LAUNCHER_FILE;

    @ConfigProperty(name = "axiom.agent.env.passthrough", defaultValue = DEFAULT_PASSTHROUGH)
    List<String> passthrough;

    /** Lazily installed launcher script. */
    private volatile Path launcher;

    /**
     * Returns the names of the variables passed through from the parent environment to every isolated
     * process.
     *
     * @return the passthrough variable names, trimmed, without blanks or duplicates
     */
    public List<String> passthrough() {
        List<String> names = passthrough == null ? List.of(DEFAULT_PASSTHROUGH.split(",")) : passthrough;
        return names.stream().map(String::trim).filter(n -> !n.isEmpty()).distinct().toList();
    }

    /**
     * Removes every variable from a process environment (as inherited from Axiom's own process) except the
     * passthrough variables. Call it before adding a tool's own variables.
     *
     * @param processEnvironment the mutable environment of a {@link ProcessBuilder}
     */
    public void restrictToPassthrough(Map<String, String> processEnvironment) {
        processEnvironment.keySet().retainAll(Set.copyOf(passthrough()));
    }

    /**
     * Returns the Axiom-provided variables ({@code AXIOM_*}) of an agent environment. These are the only
     * variables of the calling agent that the axiom-tools server receives.
     *
     * @param agentEnvironment the calling agent's environment (may be null)
     * @return the {@code AXIOM_*} entries, in their original order
     */
    public static Map<String, String> axiomVariables(Map<String, String> agentEnvironment) {
        Map<String, String> result = new LinkedHashMap<>();
        if (agentEnvironment != null) {
            agentEnvironment.forEach((k, v) -> {
                if (k != null && k.startsWith(AXIOM_PREFIX) && v != null) {
                    result.put(k, v);
                }
            });
        }
        return result;
    }

    /**
     * Rewrites every stdio definition in a map so it is started through the environment launcher. Remote
     * definitions are returned unchanged.
     *
     * @param servers the definitions by server name
     * @return a new map with isolated definitions, in the same order
     */
    public Map<String, McpServerDefinition> isolateAll(Map<String, McpServerDefinition> servers) {
        Map<String, McpServerDefinition> result = new LinkedHashMap<>();
        servers.forEach((name, def) -> result.put(name, isolate(def)));
        return result;
    }

    /**
     * Rewrites a stdio definition so it is started through the environment launcher, keeping only the
     * passthrough variables and the definition's own {@code env} keys. Remote definitions are returned
     * unchanged.
     *
     * @param def the definition
     * @return the isolated definition
     * @throws UncheckedIOException if the launcher cannot be installed
     */
    public McpServerDefinition isolate(McpServerDefinition def) {
        if (def == null || def.isHttpTransport() || def.command() == null) {
            return def;
        }
        return new McpServerDefinition("node", launcherArgs(def.command(), def.args(), def.env().keySet()),
                def.env(), def.type(), def.url(), def.headers(), def.timeoutMs());
    }

    /**
     * Builds the launcher arguments (everything after {@code node}) for a command.
     *
     * @param command  the real command
     * @param args     the real arguments
     * @param ownNames the names of the server's own declared variables
     * @return the launcher arguments
     */
    public List<String> launcherArgs(String command, List<String> args, Set<String> ownNames) {
        Set<String> keep = new LinkedHashSet<>(passthrough());
        keep.addAll(ownNames);
        List<String> result = new ArrayList<>();
        result.add(ensureLauncherInstalled().toAbsolutePath().toString());
        result.add("--keep");
        result.add(keep.stream().collect(Collectors.joining(",")));
        result.add("--");
        result.add(command);
        result.addAll(args);
        return result;
    }

    /**
     * Copies the launcher script to {@code ~/.axiom/mcp-launcher/} once per JVM. It has no dependencies, so no
     * {@code npm install} is needed.
     *
     * @return the launcher path
     * @throws UncheckedIOException if it cannot be written
     */
    Path ensureLauncherInstalled() {
        Path current = launcher;
        if (current != null && Files.isRegularFile(current)) {
            return current;
        }
        synchronized (this) {
            if (launcher != null && Files.isRegularFile(launcher)) {
                return launcher;
            }
            try {
                Path dir = AxiomHome.resolve(LAUNCHER_DIR_NAME);
                Files.createDirectories(dir);
                Path target = dir.resolve(LAUNCHER_FILE);
                try (InputStream is = McpEnvironmentIsolation.class.getClassLoader()
                        .getResourceAsStream(LAUNCHER_RESOURCE)) {
                    if (is == null) {
                        throw new IOException("Launcher resource not found: " + LAUNCHER_RESOURCE);
                    }
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                launcher = target;
                return target;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not install the MCP environment launcher", e);
            }
        }
    }
}
