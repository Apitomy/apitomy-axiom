# OpenCode Permissions (Requests + Allowed Tools) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make OpenCode permission requests work end to end: they show in the UI with the right tool and input,
Allow and Deny reach opencode, and auto-approval rules match. Then enforce a template's allowed tools through
opencode's `permission` config, so listed tools run without asking and all other tools ask (GitHub issues #373
and #371, one combined PR, epic #387).

**Architecture:**
- **#373, normalizer:** `OpenCodeEventNormalizer` reads the real `permission.asked` shape (`id`, `permission`,
  `patterns`, `metadata`, `tool.callID`). It already sees tool parts, so it remembers each call's tool name and
  input by `callID` and uses them for the `permission_request` (`requestId`, `toolName`, `toolInput`,
  `toolUseId`). That lets the UI attach the prompt to the right tool block and lets auto-approval rules match on
  the same tool name and fields as the tool block.
- **#371, mapper:** a new `OpenCodeSessionPermissions` mapper turns the template's Claude-style allowed tools into
  an opencode `permission` block with `"*": "ask"` as the default. `OpenCodeConfigWriter` writes that block into
  the session's `opencode.json` (the #368 file), and `DriverRequest` carries the allowed tools.
- **#371, cleanup:** the broken per-prompt `tools` map is no longer sent.

**Tech Stack:** Java 21, Jackson, JUnit 5, opencode 1.18.x.

**Spec:** GitHub issues #373 and #371.

## Background (verified facts)

**Permission events.** From the real capture `app/src/test/resources/opencode/events/1.18.33-tool-calls.jsonl`
(committed in #372):
```json
{"type":"permission.asked","properties":{"id":"per_0eeeec345001lu7adnxbwsnqB7","sessionID":"ses_…",
 "permission":"bash","patterns":["echo captured"],"metadata":{"command":"echo captured"},"always":["echo *"],
 "tool":{"messageID":"msg_…","callID":"toolu_01LG86GQEhToDrjJWeL12dQw"}}}
{"type":"permission.replied","properties":{"sessionID":"ses_…","requestID":"per_0eeeec345001lu7adnxbwsnqB7",
 "reply":"once"}}
```
- The bash tool part for that `callID` arrives as `running` (with `input.command`) **before** `permission.asked`.
- Replying `POST /session/{id}/permissions/{id}` with `{"response":"once"}` works. This is what
  `OpenCodeAssistantClient.respondPermission` already sends.
- The current `permission()` reads `requestId`/`requestID`/`permissionId`/`permissionID`/`toolName`/`toolInput`.
  None of those exist in the real event, so `requestId` is empty and replies fail.

**Permission config.** Verified on 2026-09-30 with `/tmp/opencode/perm.py`, using the config
`{"*":"ask","read":"allow","bash":{"*":"ask","echo *":"allow"},"axiom_axiom_list_tools":"allow"}` and the
`axiom` MCP server:
- `read`, `bash echo hi` and `axiom_axiom_list_tools` ran **without** a permission request.
- `bash ls` (`patterns:["ls"]`), `axiom_axiom_list_action_types` (`patterns:["*"]`) and `glob` (`patterns:
  ["*.txt"]`) each produced `permission.asked`.
- So per-MCP-tool keys (`<server>_<tool>`), bash patterns and a `"*"` default all behave as needed.

**UI contract** (`ui/src/components/assistant/AssistantChatPanel.tsx`, `permission_request` case):
- It uses `data.requestId` as the permission id, `data.toolName` and `data.toolInput`.
- It attaches the prompt to the most recent `tool_use` whose `toolName` equals `data.toolName` and that has no
  permission yet.
- Tool names from #372 are opencode's part `tool` values (`bash`, `read`, `write`, `edit`,
  `axiom_axiom_list_tools`, …).

**Session side** (`AssistantSession`):
- `checkAutoApproval(toolName, toolInput)` matches rules by exact `toolName`, then by `toolInput.<field>` regex.
- `respondToPermission` records `permission_resolved` itself, so a `permission.replied` event must **not** produce
  another one.

**Current tools plumbing to remove:**
- `AssistantSessionManager.buildOpenCodeTools(List<String>)` (~line 710) builds `{"allowed":[...]}`, which is
  passed as `DriverRequest.tools`, then `SessionSettings.tools`, then `sendPromptAsync(..., tools, ...)`.
- `OpenCodeAssistantClient.normalizePromptTools` turns that into `{"Read(*)":true,…}`, which matches nothing.

**Template allowed-tool formats:**
- `Read(*)`, `Write(*)`, `Edit(*)`, `Bash(ls *)`, `Bash(*)`, `WebSearch(*)`, `WebFetch(*)`
- `mcp__axiom__axiom_list_tools`
- Occasionally a bare name (`Read`), and possibly `mcp__server` or `mcp__server__*`.

**The task path's mapper is not reused.** `agents/opencode/.../OpenCodePermissionMapper` denies unlisted bash
commands (right for unattended tasks, wrong for interactive sessions). It also maps `Read(*)` to `read(*)`, which
matches nothing. The plan reuses only its static `mapMcpToolName`, and does not change it, so task behaviour stays
the same.

**Rulings:**
- **Unlisted tools ask; they are never denied.** That matches Claude's `--allowedTools`, which only pre-approves.
  An empty allowed list writes **no** permission block, so opencode's defaults apply as today.
- **"Always allow" is not added.** Sending opencode's `always` needs a REST/OpenAPI change. Axiom's own
  auto-approval rules, which now match, cover it. That is a follow-up.
- **`permission.replied` stays ignored,** so no duplicate `permission_resolved`.
- **`permission.updated`** (older opencode) is mapped the same way as `permission.asked`. The invented
  `session.permission.requested` and `permission.v2.asked` aliases are removed.

## Global Constraints

- 4-space indentation; Javadoc on all public types and methods; explicit types (no `var`); JUnit 5.
- No UI or REST/OpenAPI changes. Claude behaviour is unchanged. The task path (`agents/opencode`) is unchanged.
- The `permission_request` data must contain `requestId` (= opencode `id`), `toolName`, `toolInput` (object) and
  `toolUseId` (= `tool.callID`, if present).
- The permission block always has `"*": "ask"` first when the allowed list is non-empty. Listed tools become
  `"allow"`. Bash patterns go in an object `{"*":"ask", "<pattern>":"allow", …}`, unless unrestricted `Bash` or
  `Bash(*)` is listed, in which case it is `"bash":"allow"`.
- Commit style: conventional commits. No AI attribution. **Do not put closing keywords directly before an issue
  number** in commits.
- Run tests: `mvn -q -pl app test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`, from the repo root.
  A JBoss LogManager warning is pre-existing noise. HTTP calls to a real opencode server must use HTTP/1.1.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java` | Modify | Real permission mapping (#373) |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizerTest.java` | Modify | Fixture-based permission tests |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionPermissions.java` | Create | Allowed tools → permission block (#371) |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionPermissionsTest.java` | Create | Mapper tests |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeConfigWriter.java` | Modify | Write `permission` into `opencode.json` |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java` | Modify | `DriverRequest.allowedTools`; wiring |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` | Modify | Pass allowed tools; drop `buildOpenCodeTools` |
| tests for the writer/factory/process | Modify | Updated signatures, real-opencode config check |
| `docs/user-guide/ai-assistant.md` | Modify | Document OpenCode allowed tools + prompts |

---

### Task 1: Map real permission events (#373)

**Files:** `OpenCodeEventNormalizer.java`, `OpenCodeEventNormalizerTest.java`.

**Interfaces:**
- Produces a `permission_request` with `{requestId, toolName, toolInput, toolUseId}`.
- Internal: `private final Map<String, ToolCall> toolCalls` (callID → name and input), recorded for **every**
  tool part update, including `pending`.

- [ ] **Step 1: Write the failing tests** (add to `OpenCodeEventNormalizerTest`; replace the old
  `mapsPermissionRequestEvent` and `mapsEnvelopePermissionAskedEvent`, which use invented fields)

```java
    private static final java.util.function.Predicate<JsonNode> PERMISSION_ASKED =
            event -> "permission.asked".equals(event.path("type").asText());

    @Test
    void permissionAskedUsesIdAndToolFromPrecedingToolPart() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        normalize(first(events, toolPart("bash", "running")));

        List<SseEvent> out = normalize(first(events, PERMISSION_ASKED));

        assertEquals(1, out.size());
        SseEvent event = out.get(0);
        assertEquals("permission_request", event.type());
        assertEquals("per_0eeeec345001lu7adnxbwsnqB7", event.data().path("requestId").asText());
        assertEquals("bash", event.data().path("toolName").asText());
        assertEquals("echo captured", event.data().path("toolInput").path("command").asText());
        assertEquals("toolu_01LG86GQEhToDrjJWeL12dQw", event.data().path("toolUseId").asText());
    }

    @Test
    void permissionAskedWithoutKnownToolPartFallsBackToPermissionAndMetadata() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        SseEvent event = normalize(first(events, PERMISSION_ASKED)).get(0);

        assertEquals("per_0eeeec345001lu7adnxbwsnqB7", event.data().path("requestId").asText());
        assertEquals("bash", event.data().path("toolName").asText());
        assertEquals("echo captured", event.data().path("toolInput").path("command").asText());
    }

    @Test
    void permissionForEditUsesToolPartNameNotPermissionKey() throws Exception {
        normalizer.normalize("message", mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"p1","sessionID":"s1",
                 "messageID":"m1","type":"tool","tool":"write","callID":"c1",
                 "state":{"status":"running","input":{"filePath":"/w/tools/x.json","content":"{}"}}}}}
                """));

        SseEvent event = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"per_1","sessionID":"s1","permission":"edit",
                 "patterns":["tools/x.json"],"metadata":{"filepath":"/w/tools/x.json"},
                 "tool":{"messageID":"m1","callID":"c1"}}}
                """)).get(0);

        assertEquals("write", event.data().path("toolName").asText());
        assertEquals("/w/tools/x.json", event.data().path("toolInput").path("filePath").asText());
        assertEquals("c1", event.data().path("toolUseId").asText());
    }

    @Test
    void legacyPermissionUpdatedEventIsMappedLikeAsked() throws Exception {
        SseEvent event = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.updated","properties":{"id":"per_2","sessionID":"s1","type":"bash",
                 "pattern":"ls","metadata":{"command":"ls"},"callID":"c9"}}
                """)).get(0);

        assertEquals("permission_request", event.type());
        assertEquals("per_2", event.data().path("requestId").asText());
        assertEquals("bash", event.data().path("toolName").asText());
        assertEquals("ls", event.data().path("toolInput").path("command").asText());
        assertEquals("c9", event.data().path("toolUseId").asText());
    }

    @Test
    void permissionRepliedEmitsNothing() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        assertTrue(normalize(first(events,
                event -> "permission.replied".equals(event.path("type").asText()))).isEmpty());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=OpenCodeEventNormalizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new permission tests FAIL (empty `requestId` / `toolName`).

- [ ] **Step 3: Implement**

In `OpenCodeEventNormalizer`:
1. Add `private record ToolCall(String name, JsonNode input) { }` and
   `private final Map<String, ToolCall> toolCalls = new ConcurrentHashMap<>();`.
2. At the top of `mapToolPart`, **before** the `pending` early return, record the call:

```java
        if (!callId.isEmpty()) {
            JsonNode input = state.path("input");
            ToolCall previous = toolCalls.get(callId);
            boolean hasInput = input.isObject() && input.size() > 0;
            if (previous == null || hasInput) {
                toolCalls.put(callId, new ToolCall(part.path("tool").asText(""),
                        hasInput ? input : JsonNodeFactory.instance.objectNode()));
            }
        }
```

(The existing `callId`/`state` locals are declared first; move this block right after them.)

3. In `normalize`, replace the permission case with
   `case "permission.asked", "permission.updated" -> List.of(permission(eventData));`
4. Replace `permission(JsonNode)` with:

```java
    private SseEvent permission(JsonNode payload) {
        String callId = firstNonBlank(payload.path("tool").path("callID").asText(""),
                payload.path("callID").asText(""));
        ToolCall call = callId.isEmpty() ? null : toolCalls.get(callId);
        String permissionKey = firstNonBlank(payload.path("permission").asText(""),
                payload.path("type").asText(""));
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("requestId", payload.path("id").asText(""));
        data.put("toolName", call != null && !call.name().isEmpty() ? call.name() : permissionKey);
        if (call != null && call.input().size() > 0) {
            data.set("toolInput", call.input());
        } else if (payload.path("metadata").isObject()) {
            data.set("toolInput", payload.path("metadata"));
        } else {
            data.set("toolInput", JsonNodeFactory.instance.objectNode());
        }
        if (!callId.isEmpty()) {
            data.put("toolUseId", callId);
        }
        return new SseEvent("permission_request", data);
    }
```

5. Add `"permission.replied"` to `IGNORED_EVENT_TYPES` if it isn't there already (it is added by #372; keep it).

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='OpenCode*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS. If a driver test fake used the removed `session.permission.requested` or `permission.v2.asked`
names, change the fake to a real `permission.asked` payload (`{"type":"permission.asked","properties":{"id":...,
"sessionID":...,"permission":...}}`), keeping the test's intent, and list the change in the report.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/
git commit -m "fix(assistant): map real OpenCode permission requests (#373)"
```

---

### Task 2: Allowed tools → OpenCode permission block (#371)

**Files:** create `OpenCodeSessionPermissions.java` and `OpenCodeSessionPermissionsTest.java` (package
`io.apitomy.axiom.app.assistant.runtime.opencode`).

**Interfaces:**
- Produces `public static ObjectNode OpenCodeSessionPermissions.fromAllowedTools(List<String> allowedTools)`. It
  returns `null` when the list is null or empty, or has only blank entries. Otherwise it returns an ordered object
  whose first key is `"*": "ask"`.
- Consumes `io.apitomy.axiom.agents.opencode.OpenCodePermissionMapper.mapMcpToolName(String)`. The app already
  depends on `apitomy-axiom-agents-opencode`.

Mapping rules (names are case-sensitive, as in templates). Parse each entry as `Name` or `Name(pattern)`, trimmed.
A pattern of `*` or empty means "whole tool".

| Axiom | opencode key |
|---|---|
| `Read` | `read` |
| `Write`, `Edit`, `MultiEdit`, `NotebookEdit` | `edit` |
| `Glob` | `glob` |
| `Grep` | `grep` |
| `LS` | `list` |
| `Bash` | `bash` |
| `WebFetch` | `webfetch` |
| `WebSearch` | `websearch` |
| `Task`, `Agent` | `task` |
| `TodoWrite`, `TodoRead` | `todowrite` |
| `mcp__s__t` | `mapMcpToolName` → `s_t` |
| `mcp__s`, `mcp__s__*` | `s_*` |
| anything else | skipped, logged at DEBUG |

- **Whole tool:** `key: "allow"`. This overrides any pattern object for the same key.
- **Tool with a pattern**, e.g. `Bash(ls *)` or `Read(src/**)`: `key: {"*":"ask", "<pattern>":"allow", …}`, merged
  across entries, unless the key is already `"allow"`.

- [ ] **Step 1: Write the failing tests**

```java
package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeSessionPermissionsTest {

    @Test
    void emptyOrNullAllowedToolsProduceNoPermissionBlock() {
        assertNull(OpenCodeSessionPermissions.fromAllowedTools(null));
        assertNull(OpenCodeSessionPermissions.fromAllowedTools(List.of()));
        assertNull(OpenCodeSessionPermissions.fromAllowedTools(List.of(" ")));
    }

    @Test
    void configAssistantToolsMapToAllowRulesWithAskDefault() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(List.of(
                "Read(*)", "Write(*)", "Edit(*)", "Bash(ls *)", "Bash(cat *)",
                "mcp__axiom__axiom_list_tools"));

        assertEquals("*", permission.fieldNames().next());
        assertEquals("ask", permission.path("*").asText());
        assertEquals("allow", permission.path("read").asText());
        assertEquals("allow", permission.path("edit").asText());
        assertEquals("ask", permission.path("bash").path("*").asText());
        assertEquals("allow", permission.path("bash").path("ls *").asText());
        assertEquals("allow", permission.path("bash").path("cat *").asText());
        assertEquals("allow", permission.path("axiom_axiom_list_tools").asText());
    }

    @Test
    void unrestrictedBashWinsOverPatterns() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("Bash(ls *)", "Bash(*)", "Bash(cat *)"));

        assertEquals("allow", permission.path("bash").asText());
    }

    @Test
    void bareNamesAndWebToolsMap() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("Read", "Glob", "Grep", "WebFetch(*)", "WebSearch(*)", "Task"));

        assertEquals("allow", permission.path("read").asText());
        assertEquals("allow", permission.path("glob").asText());
        assertEquals("allow", permission.path("grep").asText());
        assertEquals("allow", permission.path("webfetch").asText());
        assertEquals("allow", permission.path("websearch").asText());
        assertEquals("allow", permission.path("task").asText());
    }

    @Test
    void wholeMcpServerMapsToWildcardKey() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("mcp__github", "mcp__jira__*"));

        assertEquals("allow", permission.path("github_*").asText());
        assertEquals("allow", permission.path("jira_*").asText());
    }

    @Test
    void readWithPathPatternUsesObjectSyntax() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(List.of("Read(src/**)"));

        assertEquals("ask", permission.path("read").path("*").asText());
        assertEquals("allow", permission.path("read").path("src/**").asText());
    }

    @Test
    void unknownToolsAreSkipped() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("StructuredOutput", "Read(*)"));

        assertFalse(permission.has("structuredoutput"));
        assertFalse(permission.has("StructuredOutput"));
        assertEquals("allow", permission.path("read").asText());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=OpenCodeSessionPermissionsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (class missing).

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.agents.opencode.OpenCodePermissionMapper;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Converts an interactive session template's allowed tools (Claude Code format, e.g. {@code Read(*)},
 * {@code Bash(ls *)}, {@code mcp__axiom__axiom_list_tools}) into an OpenCode {@code permission} config block.
 *
 * <p>Listed tools are allowed without prompting and every other tool asks the user, mirroring Claude Code's
 * {@code --allowedTools}. Unlike the unattended task path ({@link OpenCodePermissionMapper}), nothing is denied.
 */
public final class OpenCodeSessionPermissions {

    private static final Logger LOG = Logger.getLogger(OpenCodeSessionPermissions.class);
    private static final String ALLOW = "allow";
    private static final String ASK = "ask";

    private static final Map<String, String> TOOL_KEYS = Map.ofEntries(
            Map.entry("Read", "read"),
            Map.entry("Write", "edit"),
            Map.entry("Edit", "edit"),
            Map.entry("MultiEdit", "edit"),
            Map.entry("NotebookEdit", "edit"),
            Map.entry("Glob", "glob"),
            Map.entry("Grep", "grep"),
            Map.entry("LS", "list"),
            Map.entry("Bash", "bash"),
            Map.entry("WebFetch", "webfetch"),
            Map.entry("WebSearch", "websearch"),
            Map.entry("Task", "task"),
            Map.entry("Agent", "task"),
            Map.entry("TodoWrite", "todowrite"),
            Map.entry("TodoRead", "todowrite"));

    private OpenCodeSessionPermissions() {
    }

    /**
     * Builds the OpenCode permission block for a session.
     *
     * @param allowedTools the template's resolved allowed tools; may be null
     * @return the permission block (first key {@code "*": "ask"}), or null when there are no allowed tools, in
     *         which case OpenCode's default permissions apply
     */
    public static ObjectNode fromAllowedTools(List<String> allowedTools) {
        if (allowedTools == null) {
            return null;
        }
        ObjectNode permission = JsonNodeFactory.instance.objectNode();
        permission.put("*", ASK);
        boolean any = false;
        for (String raw : allowedTools) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            if (entry.startsWith("mcp__")) {
                String remainder = entry.substring(5);
                String key = !remainder.contains("__") || remainder.endsWith("__*")
                        ? remainder.replace("__*", "") + "_*"
                        : OpenCodePermissionMapper.mapMcpToolName(entry);
                permission.put(key, ALLOW);
                any = true;
                continue;
            }
            String name = entry;
            String pattern = null;
            int open = entry.indexOf('(');
            if (open > 0 && entry.endsWith(")")) {
                name = entry.substring(0, open).trim();
                pattern = entry.substring(open + 1, entry.length() - 1).trim();
            }
            String key = TOOL_KEYS.get(name);
            if (key == null) {
                LOG.debugf("No OpenCode permission key for allowed tool '%s'; skipping", entry);
                continue;
            }
            any = true;
            if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) {
                permission.put(key, ALLOW);
            } else if (!ALLOW.equals(permission.path(key).asText(null))) {
                JsonNode existing = permission.get(key);
                ObjectNode patterns = existing != null && existing.isObject()
                        ? (ObjectNode) existing
                        : permission.putObject(key).put("*", ASK);
                patterns.put(pattern, ALLOW);
            }
        }
        return any ? permission : null;
    }
}
```

- [ ] **Step 4: Run to verify pass**, same command → all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionPermissions.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionPermissionsTest.java
git commit -m "feat(assistant): map session allowed tools to OpenCode permissions (#371)"
```

---

### Task 3: Write the permission block and stop sending the per-prompt tools map

**Files:**
- Modify: `OpenCodeConfigWriter.java` and its test
- Modify: `InteractiveSessionDriverFactory.java` and its test
- Modify: `AssistantSessionManager.java`
- Modify: `OpenCodeSessionServerProcessTest.java` (real-opencode check)
- Modify: `docs/user-guide/ai-assistant.md`

**Interfaces:**
- **`OpenCodeConfigWriter`:**
  - `buildConfig(Map<String, McpServerConfig> servers, ObjectNode permission)`: sets `"permission"` when
    `permission` is non-null. The `mcp` block is written as today.
  - `writeConfig(Path sessionDirectory, Map<String, McpServerConfig> servers, ObjectNode permission)`: returns
    null (writes nothing) only when there are no servers **and** `permission` is null.
  - The 1-arg `buildConfig` and 2-arg `writeConfig` delegate with `null`.
- **`DriverRequest`:** gains a trailing component `List<String> allowedTools` (null → `List.of()`). The existing
  `tools` component stays but receives `null` from the manager.
- **Factory:** calls `writeConfig(sessionDir, mcpServers, OpenCodeSessionPermissions.fromAllowedTools(
  request.allowedTools()))` and passes `null` for the `SessionSettings` tools.

- [ ] **Step 1: Write the failing tests**

In `OpenCodeConfigWriterTest` add:

```java
    @Test
    void writesPermissionBlockEvenWithoutMcpServers(@TempDir Path sessionDir) throws Exception {
        ObjectNode permission = OpenCodeSessionPermissions.fromAllowedTools(List.of("Read(*)", "Bash(ls *)"));

        Path written = OpenCodeConfigWriter.writeConfig(sessionDir, Map.of(), permission);

        JsonNode parsed = MAPPER.readTree(Files.readString(written));
        assertEquals("ask", parsed.path("permission").path("*").asText());
        assertEquals("allow", parsed.path("permission").path("read").asText());
        assertEquals("allow", parsed.path("permission").path("bash").path("ls *").asText());
    }

    @Test
    void omitsPermissionBlockWhenNull() {
        assertFalse(OpenCodeConfigWriter.buildConfig(
                Map.of("remote", McpServerConfig.http("http://x/mcp")), null).has("permission"));
    }
```

(Imports: `com.fasterxml.jackson.databind.node.ObjectNode`.)

In `InteractiveSessionDriverFactoryTest`:
- Add `List.of()` as a new last argument to every existing `DriverRequest(...)` call.
- Add:

```java
    @Test
    void createDriverWritesPermissionsAndStopsSendingPromptTools(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "axiom-config-assistant", sessionDir, sessionDir, List.of(), Map.of(),
                        null, null, event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of(), null,
                        List.of("Read(*)", "mcp__axiom__axiom_list_tools")));

        JsonNode config = new ObjectMapper().readTree(Files.readString(sessionDir.resolve("opencode.json")));
        assertEquals("allow", config.path("permission").path("axiom_axiom_list_tools").asText());
        assertNull(getField(driver, "tools"));
    }
```

(Import `JsonNode`, `ObjectMapper` and `assertNull` if they are missing.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest='OpenCodeConfigWriterTest,InteractiveSessionDriverFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement**
- **`OpenCodeConfigWriter`:** add the overloads described above. In `buildConfig`, after the `mcp` block:
  `if (permission != null) { root.set("permission", permission); }`. Update the class Javadoc: the file now holds
  MCP servers and session permissions.
- **`DriverRequest`:**
  - Add a trailing `List<String> allowedTools` component. Add a Javadoc `@param`: "resolved allowed tools, in
    Claude Code format; enforced for OpenCode through its permission config".
  - In the compact constructor, add `allowedTools = allowedTools != null ? List.copyOf(allowedTools) :
    List.of();`.
- **Factory OpenCode branch:**
  - Use `OpenCodeConfigWriter.writeConfig(request.sessionDirectory(), request.mcpServers(),
    OpenCodeSessionPermissions.fromAllowedTools(request.allowedTools()))`.
  - In `SessionSettings`, pass `null` instead of `request.tools()`.
- **`AssistantSessionManager`:**
  - Delete `buildOpenCodeTools` and the `openCodeTools` local variable.
  - In the `DriverRequest` call, pass `null` for `tools` and add `resolvedAllowedTools` as the new last argument.
  - Remove imports that are now unused (`ArrayNode` only if nothing else uses it).
- Run `grep -rn "DriverRequest(" app/src --include=*.java` and confirm every call site is updated.

- [ ] **Step 4: Real-opencode config check**

Add to `OpenCodeSessionServerProcessTest` (it is skipped when opencode isn't installed; use an HTTP/1.1 client as
the existing `realOpenCodeUsesProcessWorkingDirectory` test does):

```java
    @Test
    void realOpenCodeLoadsSessionPermissionsFromGeneratedConfig(@TempDir Path sessionDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        Path config = OpenCodeConfigWriter.writeConfig(sessionDir, Map.of(),
                OpenCodeSessionPermissions.fromAllowedTools(List.of("Read(*)", "Bash(ls *)")));
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of("OPENCODE_CONFIG", config.toString()), sessionDir);
        try {
            process.start();
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(process.baseUrl() + "/config")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode permission = new ObjectMapper().readTree(response.body()).path("permission");
            assertEquals("ask", permission.path("*").asText(), response.body());
            assertEquals("allow", permission.path("read").asText(), response.body());
            assertEquals("allow", permission.path("bash").path("ls *").asText(), response.body());
        } finally {
            process.stop();
        }
    }
```

If opencode reports `permission` in a different but equivalent form, for example the rules normalized into an
ordered list, adjust **only the assertions** to that form, and quote the actual body in the report.

- [ ] **Step 5: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='OpenCode*Test,InteractiveSessionDriverFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS. The existing `OpenCodeAssistantClientPermissionContractTest` tools test still passes, because
the client method is unchanged.

- [ ] **Step 6: User guide**

In the OpenCode part of the `!!! note` block in `docs/user-guide/ai-assistant.md` (4-space indented, wrapped at
110 columns), add:

```markdown
    For OpenCode sessions, the template's **Allowed Tools** are written as OpenCode permission rules: listed tools
    (for example `Read(*)`, `Bash(ls *)` or `mcp__axiom__axiom_list_tools`) run without asking, and any other tool
    asks for approval in the chat. Auto-approval rules apply to these requests. When a template has no allowed
    tools, OpenCode's default permissions apply.
```

- [ ] **Step 7: Full module run**

Run `mvn -q -pl app test`. Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add -A app/src docs/user-guide/ai-assistant.md
git commit -m "feat(assistant): enforce allowed tools for OpenCode sessions via permission config (#371)"
```

- [ ] **Step 9: Manual end-to-end (human)**

Start a Configuration Assistant session on `opencode`.
1. Ask it to list the configured tools. `axiom_axiom_list_tools` runs with no prompt.
2. Ask it to "use glob to find json files". An approval prompt appears on the `glob` block. **Allow** runs it;
   **Deny** in a second attempt makes the tool fail and ends the current reply.
3. Add an auto-approval rule for `glob` and repeat. It is approved automatically.

---

## Self-Review Notes

- **#373:**
  - The id, tool name, input and `permission.updated` → Task 1.
  - Auto-approval matching: the tool name and input now come from the tool part, so they match the tool block
    fields the UI uses to create rules → Task 1.
  - `always` → a ruled follow-up.
  - `permission.replied` → ignored on purpose, to avoid a duplicate `permission_resolved`.
- **#371:**
  - Permission config and MCP key mapping → Task 2.
  - Applied at server start → Task 3.
  - The broken per-prompt tools map is no longer sent → Task 3.
  - Acceptance → Task 3, Step 9.
- **Names are consistent:** `OpenCodeSessionPermissions.fromAllowedTools`,
  `writeConfig(Path, Map, ObjectNode)`, `DriverRequest.allowedTools`, and the permission fields `requestId`,
  `toolName`, `toolInput`, `toolUseId`.
