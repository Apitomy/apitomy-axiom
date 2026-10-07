package io.apitomy.axiom.core.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static checks for node-mode action type modules: import specifiers, the {@code run} export, syntax (via
 * {@code node --check}) and {@code inputs.X} reads.
 */
public final class NodeModuleChecks {

    private static final Set<String> BUILTINS = Set.of(
            "assert", "async_hooks", "buffer", "child_process", "cluster", "console", "constants", "crypto",
            "dgram", "diagnostics_channel", "dns", "domain", "events", "fs", "fs/promises", "http", "http2",
            "https", "inspector", "module", "net", "os", "path", "path/posix", "path/win32", "perf_hooks",
            "process", "punycode", "querystring", "readline", "readline/promises", "repl", "stream",
            "stream/promises", "stream/web", "string_decoder", "timers", "timers/promises", "tls",
            "trace_events", "tty", "url", "util", "util/types", "v8", "vm", "wasi", "worker_threads", "zlib");

    /** Matches static imports ({@code import ... from 'x'}, {@code import 'x'}), dynamic imports and requires. */
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "\\bimport\\s+[^'\";]*?\\bfrom\\s*(['\"])([^'\"]+)\\1"
                    + "|\\bimport\\s*(['\"])([^'\"]+)\\3"
                    + "|\\bimport\\s*\\(\\s*(['\"])([^'\"]+)\\5\\s*\\)"
                    + "|\\brequire\\s*\\(\\s*(['\"])([^'\"]+)\\7\\s*\\)");

    private static final Pattern RUN_DECL_PATTERN = Pattern.compile(
            "\\bexport\\s+(?:async\\s+function\\s+run|function\\s+run|const\\s+run|let\\s+run)\\b");
    private static final Pattern EXPORT_LIST_PATTERN = Pattern.compile("\\bexport\\s*\\{([^}]*)\\}");
    private static final Pattern RUN_WORD_PATTERN = Pattern.compile("\\brun\\b");
    private static final Pattern INPUT_READ_PATTERN =
            Pattern.compile("(?<![\\w$.])inputs\\.([A-Za-z_$][\\w$]*)");

    private static final long CHECK_TIMEOUT_SECONDS = 10;

    private NodeModuleChecks() {
    }

    /**
     * Extracts module specifiers from static imports, dynamic imports and {@code require} calls.
     *
     * @param code the module source
     * @return the specifiers in source order
     */
    public static List<String> importSpecifiers(String code) {
        List<String> specs = new ArrayList<>();
        Matcher m = IMPORT_PATTERN.matcher(code);
        while (m.find()) {
            for (int g = 2; g <= 8; g += 2) {
                if (m.group(g) != null) {
                    specs.add(m.group(g));
                    break;
                }
            }
        }
        return specs;
    }

    /**
     * Returns whether a specifier names a Node.js built-in module.
     *
     * @param specifier the import specifier
     * @return true for {@code node:*} or a known built-in name
     */
    public static boolean isBuiltin(String specifier) {
        return specifier.startsWith("node:") || BUILTINS.contains(specifier);
    }

    /**
     * Returns whether the module exports a {@code run} binding.
     *
     * @param code the module source
     * @return true if a recognised {@code run} export is present
     */
    public static boolean exportsRun(String code) {
        if (RUN_DECL_PATTERN.matcher(code).find()) {
            return true;
        }
        Matcher m = EXPORT_LIST_PATTERN.matcher(code);
        while (m.find()) {
            if (RUN_WORD_PATTERN.matcher(m.group(1)).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks module syntax with {@code node --check}.
     *
     * @param code the module source
     * @return node's stderr when the check fails, otherwise empty
     * @throws IllegalStateException with message {@code "node unavailable"} if node cannot be started
     */
    public static Optional<String> syntaxError(String code) {
        Path file;
        Path errFile;
        try {
            file = Files.createTempFile("axiom-node-check-", ".mjs");
            errFile = Files.createTempFile("axiom-node-check-", ".err");
            Files.writeString(file, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write temp module", e);
        }
        try {
            Process process;
            try {
                process = new ProcessBuilder("node", "--check", file.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(errFile.toFile())
                        .start();
            } catch (IOException e) {
                throw new IllegalStateException("node unavailable", e);
            }
            try {
                if (!process.waitFor(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("node --check timed out");
                }
                if (process.exitValue() == 0) {
                    return Optional.empty();
                }
                return Optional.of(Files.readString(errFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("node --check failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException("node --check interrupted", e);
            }
        } finally {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(errFile);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * Collects names read via {@code inputs.<identifier>}.
     *
     * @param code the module source
     * @return the input names read
     */
    public static Set<String> inputReads(String code) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = INPUT_READ_PATTERN.matcher(code);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
