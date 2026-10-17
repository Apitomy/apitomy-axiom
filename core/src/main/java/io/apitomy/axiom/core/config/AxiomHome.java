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
     * @return the configured {@code axiom.home}, or {@code ~/.axiom}, as an absolute, normalized path (a
     *         relative {@code axiom.home} is resolved against the working directory)
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
        return of(configured);
    }

    /**
     * Turns a configured {@code axiom.home} value into the home directory. Used where the configuration is not
     * yet available through {@link ConfigProvider}, such as while building the configuration itself.
     *
     * @param configured the configured value; {@code null} or blank means the default {@code ~/.axiom}
     * @return the home directory as an absolute, normalized path
     */
    public static Path of(String configured) {
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".axiom").toAbsolutePath().normalize();
        }
        return Path.of(configured).toAbsolutePath().normalize();
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
