# OpenCode Session Working Directory and Environment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Run each OpenCode assistant session's `opencode serve` process in the session's working directory,
with the template/project environment, the same way Claude Code sessions already run (GitHub issue #369,
epic #387).

**Architecture:** `AssistantSessionManager` already resolves the working directory (template dir, project
workspace, or `<sessionDir>/workDir`) and the environment (template env + `AXIOM_PROJECT_*`) and passes both in
`DriverRequest`. The Claude driver applies them (`ClaudeInteractiveSessionDriver.java:80-85`); the OpenCode branch
of `InteractiveSessionDriverFactory` drops them. We add a working-directory parameter to
`OpenCodeSessionServerProcess` (applied via `ProcessBuilder.directory`) and have the factory pass
`request.workingDirectory()` plus `request.environment()` merged with the Axiom-managed `OPENCODE_CONFIG`.

**Tech Stack:** Java 21, Quarkus, JUnit 5, opencode 1.18.x.

**Spec:** GitHub issue #369 ("OpenCode sessions: honor session working directory and environment").

## Background (verified facts)

Verified against opencode 1.18.33 on 2026-09-29:
- Starting `opencode serve` with process cwd `/tmp/opencode/wd1` gives `GET /path` →
  `"directory":"/tmp/opencode/wd1"`, and `POST /session` returns a session whose `directory` is
  `/tmp/opencode/wd1`. So **the process working directory alone** sets the directory tools operate in.
- `?directory=<dir>` on a request selects a *different* opencode instance for that request (e.g. `POST /session?
  directory=/tmp/opencode/wd2` → session directory `wd2`). Events for that instance are not on the default
  `/event` stream the driver subscribes to.

**Ruling vs. the issue text:** the issue proposes "Pass `?directory=<workingDir>` on opencode API calls". This
plan does **not** do that. Setting the process working directory already achieves it for every call. Adding
`?directory=` everywhere would have to be kept consistent across the session, prompt, permission, abort, MCP and
event-stream calls, or events would silently go missing. The real-opencode test in Task 1 checks that
`GET /path` reports the session working directory.

Other facts:
- `OpenCodeSessionServerProcess` (`app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/`)
  currently has 4-arg and 5-arg constructors (the 5th is `Map<String, String> environment`, stored with
  `Map.copyOf`, so **null keys/values throw NPE**) and package-private `ProcessBuilder createProcessBuilder(int
  port)`.
- The factory's OpenCode branch (`InteractiveSessionDriverFactory.java:112-124`) currently builds a
  `serverEnvironment` containing only `OPENCODE_CONFIG`.
- `AssistantSessionManager` creates the working directory before creating the driver, and validates that a
  template working directory exists (`AssistantSessionManager.java:191-208`). No change is needed there.
- `InteractiveSessionDriverFactoryTest` already has a `defaultFactory()` helper plus `extractProcess(driver)` and
  `getField(target, name)` reflection helpers.

## Global Constraints

- 4-space indentation; Javadoc on all public types and methods; explicit types (no `var`); JUnit 5.
- Do not change Claude Code behavior or the REST API / OpenAPI spec.
- Out of scope (separate issues): system prompt (#370), allowed tools/permissions (#371), event normalizer
  (#372). Do not add `?directory=` query parameters (see Ruling above).
- Environment precedence for `opencode serve`: inherited JVM environment < template/project environment
  (`request.environment()`) < Axiom-managed `OPENCODE_CONFIG` (only when Axiom wrote a config file).
- Entries with a null key or null value in `request.environment()` are skipped (never passed to `Map.copyOf`).
- Commit message style: conventional commits, e.g. `fix(assistant): ...`. No AI attribution.
- Run tests with: `mvn -q -pl app test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`
  (run `mvn -q install -DskipTests` from the repo root once first if sibling modules aren't installed).

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java` | Modify | Accept and apply a working directory |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java` | Modify | Unit + real-opencode tests for the working directory |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java` | Modify | Pass working dir and merged environment to the OpenCode server |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java` | Modify | Wiring and precedence tests |
| `docs/user-guide/ai-assistant.md` | Modify | Document working dir/env for OpenCode |

---

### Task 1: Working directory for the OpenCode server process

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java`

**Interfaces:**
- Produces:
  - New constructor `OpenCodeSessionServerProcess(String executable, String hostname, int configuredPort, int
    startupTimeoutSeconds, Map<String, String> environment, Path workingDirectory)`. `workingDirectory` may be
    null (inherit the JVM's cwd). The existing 5-arg constructor delegates with `null`; the 4-arg constructor
    keeps delegating to the 5-arg one.
  - Private final field `Path workingDirectory` (a later task's test reads it via reflection under this exact
    name).
  - `createProcessBuilder(int port)` sets `processBuilder.directory(workingDirectory.toFile())` when
    `workingDirectory` is non-null.

- [ ] **Step 1: Write the failing tests**

Add to `OpenCodeSessionServerProcessTest` (add imports `java.net.URI`, `java.net.http.HttpClient`,
`java.net.http.HttpRequest`, `java.net.http.HttpResponse`, `com.fasterxml.jackson.databind.ObjectMapper`):

```java
    @Test
    void processBuilderUsesWorkingDirectoryWhenProvided(@TempDir Path workDir) {
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);

        ProcessBuilder builder = process.createProcessBuilder(4321);

        assertEquals(workDir.toFile(), builder.directory());
    }

    @Test
    void processBuilderInheritsDirectoryWhenWorkingDirectoryIsNull() {
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), null);

        ProcessBuilder builder = process.createProcessBuilder(4321);

        assertNull(builder.directory());
    }

    @Test
    void realOpenCodeUsesProcessWorkingDirectory(@TempDir Path workDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);
        try {
            process.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(process.baseUrl() + "/path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            String directory = new ObjectMapper().readTree(response.body()).path("directory").asText();
            assertEquals(workDir.toRealPath().toString(), Path.of(directory).toRealPath().toString());
        } finally {
            process.stop();
        }
    }
```

Also add `import static org.junit.jupiter.api.Assertions.assertNull;`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app test -Dtest=OpenCodeSessionServerProcessTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (no 6-arg constructor).

- [ ] **Step 3: Implement**

Add `import java.nio.file.Path;`. Add the field `private final Path workingDirectory;`. Change the existing 5-arg
constructor body to delegate, and add the 6-arg constructor:

```java
    /**
     * Creates a per-session OpenCode server process manager that inherits the JVM working directory.
     *
     * @param executable OpenCode executable name or path
     * @param hostname host to bind
     * @param configuredPort explicit port or {@code 0} for ephemeral
     * @param startupTimeoutSeconds startup timeout in seconds
     * @param environment extra environment variables for the server process (e.g. {@code OPENCODE_CONFIG})
     */
    public OpenCodeSessionServerProcess(String executable,
                                        String hostname,
                                        int configuredPort,
                                        int startupTimeoutSeconds,
                                        Map<String, String> environment) {
        this(executable, hostname, configuredPort, startupTimeoutSeconds, environment, null);
    }

    /**
     * Creates a per-session OpenCode server process manager.
     *
     * @param executable OpenCode executable name or path
     * @param hostname host to bind
     * @param configuredPort explicit port or {@code 0} for ephemeral
     * @param startupTimeoutSeconds startup timeout in seconds
     * @param environment extra environment variables for the server process (e.g. {@code OPENCODE_CONFIG})
     * @param workingDirectory working directory for the server process, or {@code null} to inherit the JVM's;
     *                         OpenCode uses it as the directory sessions and tools operate in
     */
    public OpenCodeSessionServerProcess(String executable,
                                        String hostname,
                                        int configuredPort,
                                        int startupTimeoutSeconds,
                                        Map<String, String> environment,
                                        Path workingDirectory) {
        this.executable = executable;
        this.hostname = hostname;
        this.configuredPort = configuredPort;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.environment = environment != null ? Map.copyOf(environment) : Map.of();
        this.workingDirectory = workingDirectory;
    }
```

In `createProcessBuilder(int port)`, after `processBuilder.environment().putAll(environment);` add:

```java
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory.toFile());
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl app test -Dtest=OpenCodeSessionServerProcessTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all tests PASS (the real-opencode tests run when opencode is installed, otherwise skip).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java
git commit -m "feat(assistant): run OpenCode session server in a given working directory (#369)"
```

---

### Task 2: Pass working directory and environment from the factory

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java:112-124`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java`
- Modify: `docs/user-guide/ai-assistant.md` (the `!!! note` block, lines ~15-28)

**Interfaces:**
- Consumes: 6-arg `OpenCodeSessionServerProcess` constructor and field `workingDirectory` (Task 1); existing
  field `environment` (`Map<String, String>`); `OpenCodeConfigWriter.writeConfig(Path, Map)`.
- Produces: package-private static helper
  `Map<String, String> DefaultInteractiveSessionDriverFactory.buildOpenCodeEnvironment(Map<String, String>
  requestEnvironment, Path openCodeConfig)`.

- [ ] **Step 1: Write the failing tests**

Add to `InteractiveSessionDriverFactoryTest` (imports: `java.util.HashMap`,
`static org.junit.jupiter.api.Assertions.assertNull` if not present):

```java
    @Test
    void createDriverRunsOpenCodeInWorkingDirectoryWithSessionEnvironment(@TempDir Path sessionDir)
            throws Exception {
        Path workDir = Files.createDirectories(sessionDir.resolve("workDir"));

        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "project-assistant", sessionDir, workDir, List.of(),
                        Map.of("AXIOM_PROJECT_ID", "42", "TEMPLATE_SECRET", "s3cr3t"),
                        42L, "demo", event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of()));

        Object process = extractProcess(driver);
        assertEquals(workDir, getField(process, "workingDirectory"));
        @SuppressWarnings("unchecked")
        Map<String, String> environment = (Map<String, String>) getField(process, "environment");
        assertEquals("42", environment.get("AXIOM_PROJECT_ID"));
        assertEquals("s3cr3t", environment.get("TEMPLATE_SECRET"));
        assertFalse(environment.containsKey("OPENCODE_CONFIG"));
    }

    @Test
    void axiomManagedOpenCodeConfigOverridesTemplateEnvironment() {
        Map<String, String> result =
                InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory.buildOpenCodeEnvironment(
                        Map.of("OPENCODE_CONFIG", "/template/value.json", "A", "1"),
                        Path.of("/session/opencode.json"));

        assertEquals("/session/opencode.json", result.get("OPENCODE_CONFIG"));
        assertEquals("1", result.get("A"));
    }

    @Test
    void templateOpenCodeConfigIsKeptWhenAxiomWroteNoConfig() {
        Map<String, String> result =
                InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory.buildOpenCodeEnvironment(
                        Map.of("OPENCODE_CONFIG", "/template/value.json"), null);

        assertEquals("/template/value.json", result.get("OPENCODE_CONFIG"));
    }

    @Test
    void nullEnvironmentEntriesAreSkipped() {
        Map<String, String> requestEnvironment = new HashMap<>();
        requestEnvironment.put("KEEP", "yes");
        requestEnvironment.put("NULL_VALUE", null);
        requestEnvironment.put(null, "null-key");

        Map<String, String> result =
                InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory.buildOpenCodeEnvironment(
                        requestEnvironment, null);

        assertEquals(Map.of("KEEP", "yes"), result);
    }
```

Also extend the existing `createDriverWritesOpenCodeConfigAndPointsServerAtIt` test (which passes
`sessionDir.resolve("work")` as the working directory) with:

```java
        assertEquals(sessionDir.resolve("work"), getField(process, "workingDirectory"));
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app test -Dtest=InteractiveSessionDriverFactoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`buildOpenCodeEnvironment` not found).

- [ ] **Step 3: Implement**

In `DefaultInteractiveSessionDriverFactory.createDriver`, replace the start of the OpenCode branch:

```java
            if ("opencode".equalsIgnoreCase(engineType)) {
                Path openCodeConfig = OpenCodeConfigWriter.writeConfig(
                        request.sessionDirectory(), request.mcpServers());
                Map<String, String> serverEnvironment =
                        buildOpenCodeEnvironment(request.environment(), openCodeConfig);
                OpenCodeSessionServerProcess openCodeSessionServerProcess =
                        new OpenCodeSessionServerProcess(resolveOpenCodeExecutable(),
                                openCodeServerHostname,
                                resolveOpenCodeServerPort(),
                                resolveOpenCodeStartupTimeoutSeconds(),
                                serverEnvironment,
                                request.workingDirectory());
```

Add the helper to `DefaultInteractiveSessionDriverFactory`:

```java
        /**
         * Builds the extra environment for a session's {@code opencode serve} process: the session's
         * template/project environment, overridden by the Axiom-managed {@code OPENCODE_CONFIG} when Axiom wrote
         * a config file. Entries with a null key or value are skipped.
         *
         * @param requestEnvironment resolved session environment; may be null
         * @param openCodeConfig path of the generated OpenCode config, or null if none was written
         * @return environment entries to add to the server process
         */
        static Map<String, String> buildOpenCodeEnvironment(Map<String, String> requestEnvironment,
                                                            Path openCodeConfig) {
            Map<String, String> environment = new LinkedHashMap<>();
            if (requestEnvironment != null) {
                requestEnvironment.forEach((String key, String value) -> {
                    if (key != null && value != null) {
                        environment.put(key, value);
                    }
                });
            }
            if (openCodeConfig != null) {
                environment.put("OPENCODE_CONFIG", openCodeConfig.toString());
            }
            return environment;
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl app test -Dtest='InteractiveSessionDriverFactoryTest,OpenCode*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS.

- [ ] **Step 5: Update the user guide**

In `docs/user-guide/ai-assistant.md`, inside the `!!! note` block, append a paragraph after the MCP paragraph
(4-space indented like the surrounding text, wrapped at 110 columns):

```markdown
    OpenCode sessions run in the same working directory as Claude Code sessions: the template's working
    directory if set, otherwise the project workspace for project sessions, otherwise a directory created
    inside the session directory. The template's environment variables and the `AXIOM_PROJECT_*` variables are
    passed to the session's `opencode serve` process, so they are available to OpenCode's tools and MCP
    servers.
```

- [ ] **Step 6: Full module test run**

Run: `mvn -q -pl app test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java \
        docs/user-guide/ai-assistant.md
git commit -m "fix(assistant): pass working directory and environment to OpenCode sessions (#369)"
```

- [ ] **Step 8: Manual end-to-end verification (human)**

1. `./dev.sh`, set the Configuration Assistant template's agent type to `opencode` with a valid
   `provider/model`, start a session.
2. `ls -l /proc/$(pgrep -f "opencode serve" | head -1)/cwd` → points at
   `~/.axiom/assistant/sessions/<id>/workDir`.
3. `tr '\0' '\n' < /proc/<pid>/environ | grep -E 'OPENCODE_CONFIG|AXIOM_'` shows the expected variables.
4. Ask the assistant to create a simple tool. The file should appear under `workDir/tools/`. Note: the
   generated-items panel refreshes on `tool_result` events, which OpenCode sessions don't emit yet (#372), so
   refresh the page to see it. **Apply** should then import it.
5. For a project session: the `opencode serve` cwd is the project workspace.

---

## Self-Review Notes

- Issue #369 requirements → tasks:
  - Set the process working directory to the session working directory → Tasks 1 and 2.
  - Pass the resolved environment → Task 2.
  - `?directory=` → deliberately not done; see the Ruling in Background (process cwd verified to be enough).
  - Acceptance: files land in workDir and show in items (Step 8.4); `AXIOM_PROJECT_*` visible (Step 8.3 and
    the Task 2 test).
- Names are consistent across tasks: the 6-arg constructor, field `workingDirectory`, field `environment`, and
  `buildOpenCodeEnvironment(Map<String,String>, Path)`.
- Security: template secrets reach the opencode process the same way they reach Claude (process env). The
  generated `opencode.json` is unchanged.
