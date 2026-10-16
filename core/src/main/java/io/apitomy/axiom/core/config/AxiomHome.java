package io.apitomy.axiom.core.config;

import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.file.Path;

/**
 * Resolves Axiom's home directory, where sessions, task files, installed MCP servers, workspaces and the
 * encryption key live. Configured by {@code axiom.home} (system property, environment variable or
 * {@code application.properties}); defaults to {@code ~/.axiom}. Test runs set it to a directory under
 * {@code target/} so they never touch a developer's real {@code ~/.axiom}.
 */
public final class AxiomHome {

    /** The configuration key. */
    public static final String PROPERTY = "axiom.home";

    private AxiomHome() {
    }

    /**
     * Returns the Axiom home directory.
     *
     * @return the configured {@code axiom.home}, or {@code ~/.axiom}
     */
    public static Path path() {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            try {
                configured = ConfigProvider.getConfig().getOptionalValue(PROPERTY, String.class).orElse(null);
            } catch (RuntimeException e) {
                configured = null;
            }
        }
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".axiom");
        }
        return Path.of(configured);
    }

    /**
     * Resolves a path under the Axiom home directory.
     *
     * @param first the first path element
     * @param more  further path elements
     * @return the resolved path
     */
    public static Path resolve(String first, String... more) {
        return path().resolve(Path.of(first, more));
    }
}
