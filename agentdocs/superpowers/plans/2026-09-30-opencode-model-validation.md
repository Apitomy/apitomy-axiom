# OpenCode Session Model Fallback and Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** OpenCode sessions pick a model like this:
- Use the template's model.
- If the template has none, fall back to the configured default (`axiom.agent.opencode.model`).
- Validate the chosen model against the running opencode server. An unknown or malformed model shows a visible
  error instead of silently using opencode's own default.
- Report the effective model in a `session_init` event, so the session header shows it.

This covers GitHub issue #377, part of epic #387.

**Architecture:**
- `SessionSettings` gains `fallbackModel`. The factory fills it from `axiom.agent.opencode.model`.
- In `OpenCodeInteractiveSessionDriver.start()`, after the probe, `resolveModel()` does the following:
  1. Picks the template model, or else the fallback.
  2. Validates it with `OpenCodeAssistantClient.providers()` (`GET /config/providers`).
  3. If the template model is invalid, emits a `session_error` (`ModelUnavailable`) and tries the fallback.
  4. If neither is valid, uses opencode's own default and says so in the error.
  5. Stores the effective model, which every prompt then uses.
  6. Emits `session_init {model}`.

**Tech Stack:** Java 21, Jackson, JUnit 5, JDK `HttpServer` fakes, opencode 1.18.x.

**Spec:** GitHub issue #377.

## Background (verified facts)

- **`GET /config/providers`** on opencode 1.18.33 (checked 2026-09-30) returns:
  ```json
  {"providers":[{"id":"github-copilot","models":{"gpt-5.4":{…},"claude-sonnet-5":{…},…}},
                {"id":"opencode","models":{…}}],
   "default":{"github-copilot":"gpt-5.6-terra","opencode":"big-pickle"}}
  ```
  `models` is an **object keyed by model id**.
- A `prompt_async` with `{"model":{"providerID":"nope","modelID":"x"}}` returns **204**, and the stored user
  message has no error. Invalid models therefore fail silently today, which is the bug.
- **Current model handling:**
  - `OpenCodeAssistantClient.sendPromptAsync(sessionId, prompt, model, tools, system)` only sends `model` when the
    string contains `/`. It splits on the first `/` into `providerID`/`modelID`.
  - The driver stores `SessionSettings.model` (the template model) in its `model` field and passes it on every
    prompt.
  - `application.properties:79` has `axiom.agent.opencode.model=github-copilot/claude-sonnet-5`.
  - `InteractiveSessionDriverFactory` has no property for it yet. The task-path `OpenCodeAgent` reads it.
- **UI** (`AssistantChatPanel.tsx`, `session_init` case): if `data.model` is set it calls `onModelDetected(model)`,
  which shows the model in the session header. `data.slashCommands` is optional.
- **UI** shows `session_error` (`data.message`) as a system message. The driver already emits `session_error`
  warnings for MCP servers (`McpServerUnavailable`) the same way, after `RUNNING`.
- **`SessionSettings`** is `record SessionSettings(String sessionTitle, String model, JsonNode tools,
  Set<String> expectedMcpServers, String systemPrompt)`, with a compact constructor that normalizes the set.
  Other callers:
  - the factory (one place);
  - tests in `OpenCodeInteractiveSessionDriverTest`;
  - the legacy constructors that delegate with `systemPrompt = null`.

**Rulings:**
- **Model resolution order:**
  1. The template model, if it is valid.
  2. The fallback (`axiom.agent.opencode.model`), if it is valid.
  3. opencode's own default. In this case no `model` is sent with prompts.

  Every step down that happens because a configured model is invalid produces exactly **one** `session_error`
  (`ModelUnavailable`) naming the bad value and what is used instead. A blank template model falling back to a
  valid configured default is normal: no error.
- **"Valid"** means the string has the form `provider/model`, the provider exists in `/config/providers`, and
  the model id is a key in its `models`.
- **If `/config/providers` can't be read** (error or unexpected shape), skip validation, keep the preferred model
  (template, else fallback) as it is, and log a WARN. No user-facing error is shown, so a transient failure can't
  block a session.
- **`session_init`** is always emitted after `RUNNING`, as `{"model": <effective or "">,
  "engine": "opencode"}`. When opencode's default is used, `model` is `"<providerID>/<modelID>"`, taken from the
  `default` map of the model's provider if one can be derived, otherwise `""`.

## Global Constraints

- 4-space indentation; Javadoc on public members; explicit types (no `var`); JUnit 5.
- No UI or REST/OpenAPI changes. The Claude path is unchanged.
- The session never fails to start because of the model. Model problems are shown as `session_error` warnings.
- Every prompt uses the effective model resolved at start.
- Commit style: conventional commits. No AI attribution. No closing keywords directly before issue numbers.
- Tests: `mvn -q -pl app test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. A JBoss LogManager warning
  is pre-existing noise. Real-opencode HTTP must use HTTP/1.1.

---

### Task 1: Resolve, validate and announce the model in the driver

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java`
- Test: `OpenCodeAssistantClient*Test` (new `OpenCodeAssistantClientProvidersTest.java`)
- Test: `OpenCodeInteractiveSessionDriverTest.java`

**Interfaces:**
- **`public Map<String, Set<String>> OpenCodeAssistantClient.providers()`:**
  - Maps provider id to its model ids, in insertion order.
  - Throws `IllegalStateException` on HTTP or parse failure, or when `providers` is not an array.
- **`public Map<String, String> OpenCodeAssistantClient.defaultModels()`:**
  - Returns the `default` map. It is read by the same request if convenient; otherwise add a small record
    `ProviderCatalog(Map<String, Set<String>> models, Map<String, String> defaults)` and have one method,
    `providerCatalog()`, return it.
  - Choose the record form (it avoids two HTTP calls): `public ProviderCatalog providerCatalog()`.
- **`SessionSettings` gains a trailing `String fallbackModel`:**
  - Existing construction sites pass `null`.
  - Existing legacy constructors pass `null`.
- **Driver:**
  - The field `model` becomes non-final and volatile, holding the effective model (`null` = opencode default).
  - New private `resolveModel()`, called in `start()` right after the status moves to `RUNNING`, before
    `reportMcpServerStatus()`.

- [ ] **Step 1: Write the failing tests**

Client test (`OpenCodeAssistantClientProvidersTest`, using an `HttpServer` fake like
`OpenCodeAssistantClientMcpStatusTest`):
- Serving the real shape above (trimmed to two models per provider) → `providerCatalog().models()` has both
  providers with the right model ids, and `defaults().get("github-copilot")` is `"gpt-5.6-terra"`.
- HTTP 500 → `IllegalStateException`.

Driver tests (add `/config/providers` to `FakeOpenCodeServer`, with a configurable body and status; default body
`{"providers":[{"id":"github-copilot","models":{"claude-sonnet-5":{},"gpt-5.4":{}}}],
"default":{"github-copilot":"gpt-5.4"}}`):
1. **Valid template model:** template `github-copilot/claude-sonnet-5`, fallback `github-copilot/gpt-5.4`.
   - No `ModelUnavailable` error.
   - `session_init.model == "github-copilot/claude-sonnet-5"`.
   - The prompt body has `model.providerID/modelID` = `github-copilot` / `claude-sonnet-5`.
2. **Blank template model:** template `null`, fallback `github-copilot/gpt-5.4`.
   - No error.
   - `session_init.model == "github-copilot/gpt-5.4"`.
   - The prompt uses gpt-5.4.
3. **Unknown template model:** template `github-copilot/nope`, fallback valid.
   - Exactly one `session_error` named `ModelUnavailable`. Its message contains `github-copilot/nope` and
     `github-copilot/gpt-5.4`.
   - The prompt uses gpt-5.4.
   - Status stays `RUNNING`.
4. **Template model without a slash:** template `sonnet`, fallback valid. Same as test 3: the message contains
   `sonnet`.
5. **Neither model valid:** template `x/y`, fallback `a/b`.
   - Exactly one `ModelUnavailable` error mentioning both values and "OpenCode's default model".
   - The prompt body has **no** `model` field.
   - `session_init.model` is `""`, or the provider default as defined in the Rulings. Since providers `x` and `a`
     don't exist, expect `""`.
6. **`/config/providers` returns 500:** template `github-copilot/whatever`.
   - No `ModelUnavailable` error.
   - The prompt uses `github-copilot/whatever`.
   - `session_init.model == "github-copilot/whatever"`.

Collect events through the driver's `eventSink` (a `CopyOnWriteArrayList`). Read prompt bodies with
`server.promptBodies()`, which already exists. Construct drivers with the 6-argument
`SessionSettings(..., fallbackModel)` constructor.

- [ ] **Step 2: Run to verify failure**

Run `-Dtest='OpenCodeAssistantClientProvidersTest,OpenCodeInteractiveSessionDriverTest'`. Expected: compilation
failure.

- [ ] **Step 3: Implement**

- **Client:** add `record ProviderCatalog(Map<String, Set<String>> models, Map<String, String> defaults)` (public,
  nested, with Javadoc) and `providerCatalog()`, which uses the existing private `getJson("/config/providers")`.
  Parse `providers[].id` and `providers[].models` (object keys; if it's an array, use each element's `id`) and
  `default` (object of strings).
- **`SessionSettings`:** add `fallbackModel`. Update all construction sites in main and test sources (`grep -rn
  "new SessionSettings\|SessionSettings(" app/src`).
- **Driver `resolveModel()`:**

```java
    private void resolveModel() {
        String preferred = isBlank(templateModel) ? fallbackModel : templateModel;
        OpenCodeAssistantClient.ProviderCatalog catalog;
        try {
            catalog = client.providerCatalog();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to read OpenCode providers; using model '%s' without validation", preferred);
            model = isBlank(preferred) ? null : preferred;
            emitSessionInit();
            return;
        }
        List<String> rejected = new ArrayList<>();
        String effective = null;
        for (String candidate : candidates()) {          // template (if set), then fallback (if set and different)
            if (isValid(candidate, catalog)) {
                effective = candidate;
                break;
            }
            rejected.add(candidate);
        }
        model = effective;
        if (!rejected.isEmpty()) {
            String using = effective != null ? "'" + effective + "'" : "OpenCode's default model";
            emitWarning("ModelUnavailable", "Model " + String.join(", ", quote(rejected))
                    + " is not available in OpenCode (expected provider/model from the configured providers); using "
                    + using + ".");
        }
        emitSessionInit();
    }
```

  Implement the helpers `candidates()`, `isValid(model, catalog)` (form `p/m`, provider present, `m` in its models),
  `quote(list)`, `isBlank`, and `emitSessionInit()`, which emits `session_init` with `model` (the effective model,
  else `""`) and `engine` `"opencode"`.

  Reuse or generalise the existing `emitMcpWarning` so that the `name` is a parameter (`emitWarning(String name,
  String message)`). Keep the MCP call sites using `"McpServerUnavailable"`.

  Wrap `resolveModel()` in the same "never fail the session" `try/catch(RuntimeException)` + WARN pattern as
  `reportMcpServerStatus()`.

  Store the template model in a new final field `templateModel`, and `fallbackModel` in a final field. `model`
  (volatile, non-final) is the effective model. `sendUserMessage` already passes `model`.

- [ ] **Step 4: Run to verify pass**

Run `-Dtest='OpenCode*Test,InteractiveSessionDriverFactoryTest'`. Expected: all PASS. Existing driver tests whose
fake server lacks `/config/providers` must still pass. With the 500/404 path they keep the template model as is,
and no error is emitted.

- [ ] **Step 5: Commit** `fix(assistant): validate OpenCode session model and fall back to the default (#377)`

---

### Task 2: Wire the configured default model and verify on real opencode

**Files:**
- Modify: `InteractiveSessionDriverFactory.java` and its test
- Modify: `OpenCodeSessionServerProcessTest.java` (real-opencode test)
- Modify: `docs/user-guide/ai-assistant.md`

- [ ] **Step 1: Write the failing test**

In `InteractiveSessionDriverFactoryTest`:
- Set the new factory field `openCodeDefaultModel` (via `setField`, as `Optional.of("github-copilot/gpt-5.4")`)
  in `defaultFactory()`.
- Add a test that creating an opencode driver with template model `null` leaves the driver field `fallbackModel`
  equal to `"github-copilot/gpt-5.4"` (read via `getField`).

- [ ] **Step 2: Implement**

In `DefaultInteractiveSessionDriverFactory`:
- Add `@ConfigProperty(name = "axiom.agent.opencode.model") Optional<String> openCodeDefaultModel;`.
- Pass `openCodeDefaultModel.map(String::trim).filter(v -> !v.isEmpty()).orElse(null)` as `fallbackModel` into
  `SessionSettings`.
- Update the existing factory tests' `setField` calls if needed. An unset `Optional` field is null under
  reflection-constructed factories, so guard with `openCodeDefaultModel != null ? … : null`.

- [ ] **Step 3: Real-opencode test** (skipped when opencode isn't installed; `OpenCodeSessionServerProcessTest`)

Start a server (6-argument constructor, `@TempDir` workDir) and create a client. Assert that
`providerCatalog().models()` is not empty and that each provider has at least one model. Don't assert specific
model ids; they depend on the user's providers.

- [ ] **Step 4: Docs**

In the OpenCode part of the `!!! note` block in `docs/user-guide/ai-assistant.md` (4-space indent, at most 110
columns), add:

```markdown
    OpenCode sessions use the template's model (in `provider/model` form, for example
    `github-copilot/claude-sonnet-5`), or `axiom.agent.opencode.model` when the template has none. The model is
    checked against the providers OpenCode has configured; if it is not available, the chat shows a warning and
    the session falls back to the default model.
```

- [ ] **Step 5: Run** `-Dtest='InteractiveSessionDriverFactoryTest,OpenCode*Test'`, then the full `mvn -q -pl app
  test`.

- [ ] **Step 6: Commit** `feat(assistant): use the configured default model for OpenCode sessions (#377)`

- [ ] **Step 7: Manual (human)**
  1. **Invalid model:** set a template's model to `github-copilot/does-not-exist`. Starting a session shows a
     warning, and the header shows the default model.
  2. **No model:** clear the template model. The header shows `axiom.agent.opencode.model` and there is no
     warning.
