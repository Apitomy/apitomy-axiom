package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.McpCatalogEntry;
import io.apitomy.axiom.api.beans.McpCatalogInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog of MCP servers users can add: a curated list shipped with Axiom and a live search of the official
 * MCP Registry. Entries describe how to run a server (on demand via {@code npx}, {@code uvx}, {@code docker}, or
 * a remote URL) and which values the user must supply; they are turned into MCP servers by the UI.
 */
@ApplicationScoped
public class McpCatalogService {

    private static final Logger LOG = Logger.getLogger(McpCatalogService.class);
    private static final String CURATED_RESOURCE = "mcp-catalog/curated.json";
    private static final int REGISTRY_LIMIT = 30;
    private static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final String registryUrl;
    private volatile List<McpCatalogEntry> curated;

    /**
     * CDI constructor.
     *
     * @param objectMapper the object mapper
     * @param registryUrl base URL of the MCP Registry
     */
    @Inject
    public McpCatalogService(ObjectMapper objectMapper,
                             @ConfigProperty(name = "axiom.mcp.registry.url",
                                     defaultValue = "https://registry.modelcontextprotocol.io") String registryUrl) {
        this.objectMapper = objectMapper;
        this.registryUrl = registryUrl;
    }

    /** Thrown when the MCP Registry cannot be searched. */
    public static class RegistryUnavailableException extends RuntimeException {
        RegistryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Lists curated entries matching a search term (name, title or description; case-insensitive).
     *
     * @param search the term, or null/blank for all
     * @return the matching entries
     */
    public List<McpCatalogEntry> curated(String search) {
        return curatedEntries().stream().filter(e -> matches(e, search)).toList();
    }

    /**
     * Searches the MCP Registry (latest versions only).
     *
     * @param search the search term
     * @return the entries
     * @throws RegistryUnavailableException if the registry cannot be reached or answers with an error
     */
    public List<McpCatalogEntry> registry(String search) {
        String query = "?version=latest&limit=" + REGISTRY_LIMIT
                + (search == null || search.isBlank() ? "" : "&search=" + URLEncoder.encode(search.trim(), StandardCharsets.UTF_8));
        URI uri = URI.create(registryUrl.replaceAll("/+$", "") + "/v0/servers" + query);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(REGISTRY_TIMEOUT).build()) {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(REGISTRY_TIMEOUT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new RegistryUnavailableException("The MCP Registry returned HTTP " + response.statusCode(), null);
                }
                return fromRegistry(objectMapper.readTree(body));
            }
        } catch (IOException e) {
            throw new RegistryUnavailableException("Could not reach the MCP Registry at " + registryUrl + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegistryUnavailableException("Interrupted while searching the MCP Registry", e);
        }
    }

    /**
     * Reports which runtimes used by catalog servers are installed.
     *
     * @return {@code node}, {@code uv} and {@code docker} availability
     */
    public Map<String, Boolean> runtimes() {
        return findRuntimes(System.getenv("PATH"));
    }

    /**
     * Checks a PATH value for {@code npx}, {@code uvx} and {@code docker}.
     *
     * @param path the PATH value
     * @return availability keyed by runtime
     */
    static Map<String, Boolean> findRuntimes(String path) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        result.put("node", onPath(path, "npx"));
        result.put("uv", onPath(path, "uvx"));
        result.put("docker", onPath(path, "docker"));
        return result;
    }

    private static boolean onPath(String path, String executable) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<McpCatalogEntry> curatedEntries() {
        if (curated == null) {
            synchronized (this) {
                if (curated == null) {
                    curated = loadCurated();
                }
            }
        }
        return curated;
    }

    private List<McpCatalogEntry> loadCurated() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CURATED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + CURATED_RESOURCE);
            }
            List<McpCatalogEntry> entries = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(in).path("servers")) {
                McpCatalogEntry entry = objectMapper.treeToValue(node, McpCatalogEntry.class);
                entry.setSource(McpCatalogEntry.Source.CURATED);
                entry.setRuntime(runtimeOf(entry.getTransport().value(), entry.getCommand()));
                entries.add(entry);
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load the curated MCP catalog", e);
        }
    }

    private static boolean matches(McpCatalogEntry e, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String term = search.trim().toLowerCase(Locale.ROOT);
        return contains(e.getName(), term) || contains(e.getTitle(), term) || contains(e.getDescription(), term);
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    private static McpCatalogEntry.Runtime runtimeOf(String transport, String command) {
        if (!"stdio".equals(transport)) {
            return McpCatalogEntry.Runtime.REMOTE;
        }
        return switch (command == null ? "" : command) {
            case "uvx", "uv" -> McpCatalogEntry.Runtime.UV;
            case "docker", "podman" -> McpCatalogEntry.Runtime.DOCKER;
            default -> McpCatalogEntry.Runtime.NODE;
        };
    }

    // ── MCP Registry mapping ─────────────────────────────────────────

    /**
     * Maps an MCP Registry {@code /v0/servers} response. Each server becomes one entry, run (in order of
     * preference) from its npm or PyPI package, its remote endpoint, or its OCI image. Servers that cannot be
     * run this way are skipped. Duplicate names keep the first (latest) entry.
     *
     * @param response the registry response
     * @return the entries
     */
    static List<McpCatalogEntry> fromRegistry(JsonNode response) {
        Map<String, McpCatalogEntry> byName = new LinkedHashMap<>();
        for (JsonNode item : response.path("servers")) {
            JsonNode server = item.has("server") ? item.get("server") : item;
            String id = server.path("name").asText("");
            if (id.isEmpty() || byName.containsKey(id)) {
                continue;
            }
            toEntry(server).ifPresent(e -> byName.put(id, e));
        }
        return new ArrayList<>(byName.values());
    }

    private static Optional<McpCatalogEntry> toEntry(JsonNode server) {
        McpCatalogEntry e = new McpCatalogEntry();
        String id = server.path("name").asText();
        e.setId(id);
        e.setSource(McpCatalogEntry.Source.REGISTRY);
        e.setName(suggestName(id));
        e.setTitle(server.hasNonNull("title") ? server.get("title").asText() : id);
        e.setDescription(text(server, "description"));
        e.setVersion(text(server, "version"));
        String homepage = text(server, "websiteUrl");
        e.setHomepage(homepage != null ? homepage : text(server.path("repository"), "url"));

        JsonNode packages = server.path("packages");
        Optional<JsonNode> npm = findPackage(packages, "npm");
        Optional<JsonNode> pypi = findPackage(packages, "pypi");
        Optional<JsonNode> oci = findPackage(packages, "oci");
        JsonNode remote = firstRemote(server.path("remotes"));
        if (npm.isPresent()) {
            JsonNode p = npm.get();
            stdio(e, "npx", List.of("-y", versioned(p, "@")), p);
        } else if (pypi.isPresent()) {
            JsonNode p = pypi.get();
            stdio(e, "uvx", List.of(versioned(p, "==")), p);
        } else if (remote != null) {
            e.setTransport("sse".equals(remote.path("type").asText()) ? McpCatalogEntry.Transport.SSE
                    : McpCatalogEntry.Transport.HTTP);
            e.setUrl(remote.path("url").asText());
            for (JsonNode h : remote.path("headers")) {
                McpCatalogInput input = input(h);
                String value = text(h, "value");
                if (value != null && value.matches(".*\\{[^}]+}.*")) {
                    input.setTemplate(value.replaceAll("\\{[^}]+}", "{value}"));
                } else if (value != null && !value.isBlank()) {
                    input.setDefaultValue(value);
                } else if ("authorization".equalsIgnoreCase(input.getName())) {
                    input.setTemplate("Bearer {value}");
                }
                e.getHeaders().add(input);
            }
        } else if (oci.isPresent()) {
            JsonNode p = oci.get();
            List<String> args = new ArrayList<>(List.of("run", "-i", "--rm"));
            for (JsonNode env : p.path("environmentVariables")) {
                args.add("-e");
                args.add(env.path("name").asText());
            }
            String image = p.path("identifier").asText();
            if (p.hasNonNull("version") && !image.contains(":")) {
                image = image + ":" + p.get("version").asText();
            }
            args.add(image);
            stdio(e, "docker", args, p);
        } else {
            return Optional.empty();
        }
        e.setRuntime(runtimeOf(e.getTransport().value(), e.getCommand()));
        return Optional.of(e);
    }

    private static void stdio(McpCatalogEntry e, String command, List<String> baseArgs, JsonNode pkg) {
        e.setTransport(McpCatalogEntry.Transport.STDIO);
        e.setCommand(command);
        List<String> args = new ArrayList<>(baseArgs);
        Set<String> argNames = new LinkedHashSet<>();
        int positional = 0;
        for (JsonNode a : pkg.path("packageArguments")) {
            String value = text(a, "value") != null ? text(a, "value") : text(a, "default");
            boolean required = a.path("isRequired").asBoolean(false);
            if ("named".equals(a.path("type").asText())) {
                if (value != null) {
                    args.add(a.path("name").asText());
                    args.add(value);
                } else if (required) {
                    String name = argName(a, "arg" + (++positional));
                    args.add(a.path("name").asText());
                    args.add("{arg:" + name + "}");
                    addArgument(e, argNames, name, a);
                }
            } else if (value != null) {
                args.add(value);
            } else if (required) {
                String name = argName(a, "arg" + (++positional));
                args.add("{arg:" + name + "}");
                addArgument(e, argNames, name, a);
            }
        }
        e.setArgs(args);
        for (JsonNode env : pkg.path("environmentVariables")) {
            McpCatalogInput input = input(env);
            input.setDefaultValue(text(env, "default"));
            e.getEnv().add(input);
        }
    }

    private static void addArgument(McpCatalogEntry e, Set<String> names, String name, JsonNode a) {
        if (names.add(name)) {
            McpCatalogInput input = new McpCatalogInput();
            input.setName(name);
            input.setDescription(text(a, "description"));
            input.setRequired(true);
            input.setSecret(a.path("isSecret").asBoolean(false));
            e.getArguments().add(input);
        }
    }

    private static String argName(JsonNode a, String fallback) {
        String hint = text(a, "valueHint");
        if (hint != null) {
            return hint;
        }
        String name = text(a, "name");
        return name != null ? name.replaceAll("^-+", "") : fallback;
    }

    private static McpCatalogInput input(JsonNode node) {
        McpCatalogInput input = new McpCatalogInput();
        input.setName(node.path("name").asText());
        input.setDescription(text(node, "description"));
        input.setRequired(node.path("isRequired").asBoolean(false));
        input.setSecret(node.path("isSecret").asBoolean(false));
        return input;
    }

    private static Optional<JsonNode> findPackage(JsonNode packages, String registryType) {
        for (JsonNode p : packages) {
            if (registryType.equals(p.path("registryType").asText())
                    && "stdio".equals(p.path("transport").path("type").asText("stdio"))) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static JsonNode firstRemote(JsonNode remotes) {
        for (JsonNode r : remotes) {
            String type = r.path("type").asText();
            if (("streamable-http".equals(type) || "sse".equals(type)) && r.hasNonNull("url")
                    && !r.get("url").asText().contains("{")) {
                return r;
            }
        }
        return null;
    }

    private static String versioned(JsonNode pkg, String separator) {
        String identifier = pkg.path("identifier").asText();
        String version = text(pkg, "version");
        return version == null || version.isBlank() || "latest".equals(version) ? identifier
                : identifier + separator + version;
    }

    /**
     * Suggests a valid Axiom server name from a registry name such as {@code io.github.github/github-mcp-server}.
     *
     * @param registryName the registry name
     * @return the suggestion
     */
    static String suggestName(String registryName) {
        String last = registryName.substring(registryName.lastIndexOf('/') + 1);
        String name = last.replaceAll("[^A-Za-z0-9_-]", "-").replaceAll("_{2,}", "_")
                .replaceAll("^[^A-Za-z0-9]+", "").replaceAll("[^A-Za-z0-9]+$", "");
        if (name.length() > McpServerService.MAX_NAME_LENGTH) {
            name = name.substring(0, McpServerService.MAX_NAME_LENGTH).replaceAll("[^A-Za-z0-9]+$", "");
        }
        return name.isEmpty() ? "mcp-server" : name;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() && !v.isNull() ? v.asText() : null;
    }
}
