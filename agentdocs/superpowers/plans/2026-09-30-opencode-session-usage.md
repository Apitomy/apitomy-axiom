# OpenCode Session Cost, Tokens and Duration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Report real cost, token usage and turn duration for OpenCode sessions, and record the session's AI usage
correctly for both engines (GitHub issue #374, epic #387).

**Architecture:**
- **Normalizer:** `OpenCodeEventNormalizer` stops ignoring `message.updated`. It keeps the latest `info.cost`,
  `info.tokens` and `info.time` for each assistant message of the current turn, and emits nothing for these
  events. On `session.idle` it builds `turn_complete` from them.
- **The `turn_complete` contract** (the same for both engines):
  - `costUsd` is the **cumulative session cost**.
  - `inputTokens` and `outputTokens` are **per turn**.
  - `durationMs` is per turn.
- **`AssistantSession.accumulateCost`:** it currently adds `costUsd` up. It changes to keeping the latest
  (maximum) cumulative cost, which also fixes recorded AI usage for Claude.

**Tech Stack:** Java 21, Jackson, JUnit 5; opencode 1.18.33 fixtures (from #372).

**Spec:** GitHub issue #374.

## Background (verified facts)

**Claude contract.** Measured on 2026-09-30 by running two turns in one
`claude --print --input-format stream-json --output-format stream-json` process:

| turn | `result.total_cost_usd` | `result.usage.output_tokens` |
|---|---|---|
| 1 | 0.0293125 | 198 |
| 2 | 0.03579 | 44 |

So Claude's `total_cost_usd` is **cumulative per session**, while `usage` is **per turn**.
`AssistantEventParser.parseResult` copies these into `turn_complete` as `costUsd` (cumulative), plus
`inputTokens` (input + cache_creation + cache_read) and `outputTokens` (per turn).

**Current consumers:**
- **UI** (`ui/src/pages/AssistantSessionPage.tsx:~446`): calls `setSessionCost(cost)`, which *sets* the cost from
  the latest `turn_complete`. It *adds* the tokens. Both are correct for the contract above.
- **`AssistantSession.accumulateCost`** (`AssistantSession.java:~627`): does `totalCostUsd.add(costUsd)`, which
  **sums the cumulative values**. That is wrong for Claude today: recorded usage after two turns is 0.065 instead
  of 0.036. It sums tokens and duration, which is correct, and increments `turnCount`.
- **`AssistantSessionManager` (~415-427):** writes `getTotalCostUsd()`, the token totals and the duration total
  into an `AiUsageEntity` when the session is destroyed.

**OpenCode data.** From the fixtures in `app/src/test/resources/opencode/events/`:
- Each assistant message produces `message.updated` with `properties.info` =
  `{id, role:"assistant", cost, tokens:{input, output, reasoning, cache:{read, write}}, time:{created,
  completed?}}`.
  - The same message is updated **repeatedly**: first with 0 values, then with the final values twice.
  - One turn can contain several assistant messages: a tool-call step, then the final answer.
- **Fixture `1.18.33-tool-calls.jsonl`** has two assistant messages:
  - `…mJC5Qd`: cost 0.064489; tokens input 2, output 110, reasoning 25, cache write 25254, cache read 0; created
    1790715017273, completed 1790715020150.
  - `…5UCva5`: cost 0.0057523; tokens input 2, output 5, reasoning 0, cache write 259, cache read 25254; created
    1790715020152, completed 1790715021168.
  - So this turn's cost is **0.0702413**.
  - `inputTokens` = 2+25254+0 + 2+259+25254 = **50771**.
  - `outputTokens` = 110+25 + 5+0 = **140**.
  - `durationMs` = 1790715021168 − 1790715017273 = **3895**.
- **Fixture `1.18.33-tool-error.jsonl`:**
  - Costs 0.009248 + 0.0053619 = **0.0146099**.
  - `inputTokens` = (2+1532+23720) + (2+103+25252) = **50611**.
  - `outputTokens` = 67 + 5 = **72**.
- The same values also appear on `step-finish` parts. We use `message.updated` only, so nothing is counted twice.
- Since #375, several queued prompts can finish with **one** `session.idle`. All assistant messages seen since the
  previous idle belong to that `turn_complete`.

**Rulings:**
- **Input tokens include cache read and write,** and **output tokens include reasoning.** This matches the Claude
  parser.
- **`costUsd` on OpenCode's `turn_complete` is cumulative,** so the UI and `AssistantSession` treat both engines
  the same way.
- **`AssistantSession` keeps the maximum cumulative cost.** A `turn_complete` whose `costUsd` is missing or 0, for
  example after an abort, never lowers the total. This fixes Claude's inflated `AiUsageEntity.costUsd`, which also
  existed before this change.
- **Duration** = the latest `time.completed` minus the earliest `time.created` among the turn's assistant
  messages, or 0 if either is missing.
- **Updates that arrive after `session.idle`** for messages already counted are ignored, to avoid counting them
  twice. Settled message ids are kept for the session's lifetime, like the other de-duplication state.

## Global Constraints

- 4-space indentation; Javadoc on public members; explicit types (no `var`); JUnit 5.
- No UI or REST/OpenAPI changes. The Claude parser stays unchanged.
- `turn_complete` data keeps its existing field names: `sessionId`, `costUsd`, `inputTokens`, `outputTokens`,
  `success`, plus `durationMs`.
- `message.updated` must still produce **no** UI events (an empty list).
- Commit style: conventional commits. No AI attribution. No closing keywords directly before issue numbers.
- Tests: `mvn -q -pl app test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. A JBoss LogManager warning
  is pre-existing noise.

## File Structure

| File | Change |
|---|---|
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java` | Track assistant message usage; build `turn_complete` from it |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizerTest.java` | Fixture-based usage tests |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java` | Replay test asserts usage |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java` | Cumulative cost semantics |
| `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantSessionDriverDelegationTest.java` | Cost accumulation tests |

---

### Task 1: Usage in OpenCode `turn_complete`

**Interfaces:** `turn_complete` data = `{sessionId, costUsd (cumulative session), inputTokens, outputTokens,
durationMs, success}`.

- [ ] **Step 1: Write the failing tests** (add to `OpenCodeEventNormalizerTest`; `TOOL_CALLS`, `TOOL_ERROR`,
  `normalize(JsonNode)` and `mapper` already exist there)

```java
    private SseEvent replayUntilTurnComplete(OpenCodeEventNormalizer target, List<JsonNode> events) {
        SseEvent turnComplete = null;
        for (JsonNode event : events) {
            for (SseEvent out : target.normalize(event.path("type").asText(), event)) {
                if ("turn_complete".equals(out.type())) {
                    turnComplete = out;
                }
            }
        }
        return turnComplete;
    }

    @Test
    void turnCompleteCarriesCostTokensAndDurationFromAssistantMessages() {
        SseEvent turnComplete = replayUntilTurnComplete(normalizer, OpenCodeEventFixtures.load(TOOL_CALLS));

        assertEquals(0.0702413, turnComplete.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(50771, turnComplete.data().path("inputTokens").asLong());
        assertEquals(140, turnComplete.data().path("outputTokens").asLong());
        assertEquals(3895, turnComplete.data().path("durationMs").asLong());
    }

    @Test
    void costIsCumulativeAcrossTurnsWhileTokensArePerTurn() {
        replayUntilTurnComplete(normalizer, OpenCodeEventFixtures.load(TOOL_CALLS));

        SseEvent second = replayUntilTurnComplete(normalizer, OpenCodeEventFixtures.load(TOOL_ERROR));

        assertEquals(0.0702413 + 0.0146099, second.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(50611, second.data().path("inputTokens").asLong());
        assertEquals(72, second.data().path("outputTokens").asLong());
    }

    @Test
    void messageUpdatedEmitsNoUiEvents() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        assertTrue(events.stream()
                .filter(event -> "message.updated".equals(event.path("type").asText()))
                .allMatch(event -> normalize(event).isEmpty()));
    }

    @Test
    void lateAssistantUpdateAfterIdleIsNotCountedAgain() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        replayUntilTurnComplete(normalizer, events);
        JsonNode lateUpdate = OpenCodeEventFixtures.first(events,
                event -> "message.updated".equals(event.path("type").asText())
                        && "assistant".equals(event.path("properties").path("info").path("role").asText())
                        && event.path("properties").path("info").path("cost").asDouble() > 0);
        normalize(lateUpdate);

        SseEvent next = normalizer.normalize("message",
                payload("{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}")).get(0);

        assertEquals(0.0702413, next.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(0, next.data().path("inputTokens").asLong());
        assertEquals(0, next.data().path("outputTokens").asLong());
        assertEquals(0, next.data().path("durationMs").asLong());
    }
```

Add a tiny helper in the test if one isn't there already:

```java
    private JsonNode payload(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
```

Also update the existing `mapsTurnCompletionEvent` and `mapsSessionIdleToTurnComplete` tests. They build
`turn_complete` from invented fields (`costUsd`, `inputTokens` on the idle event). Replace their cost and token
assertions with: `costUsd == 0`, `inputTokens == 0`, `outputTokens == 0`, and `sessionId`/`success` as before
(no assistant messages seen yet). The invented top-level `costUsd`/`inputTokens` fields on idle events are no
longer read.

- [ ] **Step 2: Run to verify failure**

Run `-Dtest=OpenCodeEventNormalizerTest`. Expected: the new usage tests FAIL (0 values).

- [ ] **Step 3: Implement** (in `OpenCodeEventNormalizer`)

1. Remove `"message.updated"` from `IGNORED_EVENT_TYPES`. Add `case "message.updated" -> trackUsage(eventData);`
   so that it returns an empty list.
2. Add state:

```java
    private record MessageUsage(double cost, long inputTokens, long outputTokens, long created, long completed) {
    }

    private final Map<String, MessageUsage> turnUsage = new ConcurrentHashMap<>();
    private final Set<String> settledMessages = ConcurrentHashMap.newKeySet();
    private double sessionCostUsd;
```

3. `trackUsage`:

```java
    private List<SseEvent> trackUsage(JsonNode eventData) {
        JsonNode info = eventData.path("info");
        String messageId = info.path("id").asText("");
        if (!"assistant".equals(info.path("role").asText("")) || messageId.isEmpty()
                || settledMessages.contains(messageId)) {
            return Collections.emptyList();
        }
        JsonNode tokens = info.path("tokens");
        long input = tokens.path("input").asLong(0)
                + tokens.path("cache").path("read").asLong(0)
                + tokens.path("cache").path("write").asLong(0);
        long output = tokens.path("output").asLong(0) + tokens.path("reasoning").asLong(0);
        turnUsage.put(messageId, new MessageUsage(info.path("cost").asDouble(0), input, output,
                info.path("time").path("created").asLong(0), info.path("time").path("completed").asLong(0)));
        return Collections.emptyList();
    }
```

4. Replace `turnComplete(JsonNode)` so it sums `turnUsage`:
   - turn cost = the sum of the costs;
   - `sessionCostUsd += turnCost`;
   - tokens = the sums;
   - duration = max(completed) − min(created) over entries with created > 0 and completed > 0, or 0 if there are
     none.

   Then move all `turnUsage` keys into `settledMessages` and clear `turnUsage`. Put `costUsd` =
   `sessionCostUsd`, `inputTokens`, `outputTokens` and `durationMs`. Keep the `sessionId` and `success` logic as it
   is.

   Update the class Javadoc: `turn_complete.costUsd` is the cumulative session cost; tokens and duration are per
   turn.

- [ ] **Step 4: Driver replay assertion**

In `OpenCodeInteractiveSessionDriverTest.replaysRealToolCallStreamIntoAssistantEvents`, after the existing
assertions, add:

```java
        SseEvent turnComplete = events.get(events.size() - 1);
        assertEquals(0.0702413, turnComplete.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(140, turnComplete.data().path("outputTokens").asLong());
```

- [ ] **Step 5: Run to verify pass**

Run `-Dtest='OpenCode*Test'`. Expected: all PASS.

- [ ] **Step 6: Commit**

`fix(assistant): report OpenCode cost, tokens and duration on turn completion (#374)`

---

### Task 2: Cumulative cost semantics in `AssistantSession`

- [ ] **Step 1: Write the failing test** (add to `AssistantSessionDriverDelegationTest`; `handleDriverEvent` is
  package-private and the test is in the same package)

```java
    @Test
    void sessionCostUsesCumulativeTurnCostAndSumsTokens() throws Exception {
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), "claude-code", null, null, new RecordingDriver());
        session.start();

        session.handleDriverEvent(turnComplete(0.0293125, 10, 198, 4511));
        session.handleDriverEvent(turnComplete(0.03579, 22660, 44, 1419));
        session.handleDriverEvent(turnComplete(0.0, 0, 0, 0));

        assertEquals(0.03579, session.getTotalCostUsd(), 1e-9);
        assertEquals(22670, session.getTotalInputTokens());
        assertEquals(242, session.getTotalOutputTokens());
        assertEquals(5930, session.getTotalDurationMs());
        assertEquals(3, session.getTurnCount());
    }

    private static SseEvent turnComplete(double cost, long in, long out, long durationMs) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("costUsd", cost);
        data.put("inputTokens", in);
        data.put("outputTokens", out);
        data.put("durationMs", durationMs);
        return new SseEvent("turn_complete", data);
    }
```

Imports: `com.fasterxml.jackson.databind.node.ObjectNode` and `AssistantEventParser.SseEvent` if not already
present.

Adjust the getter names to the real ones (`getTotalCostUsd`, `getTotalInputTokens`, `getTotalOutputTokens`,
`getTotalDurationMs`, `getTurnCount`). Check them in `AssistantSession`, and check their return types (e.g.
`double`/`long`/`int`).

- [ ] **Step 2: Run to verify failure**

Run `-Dtest=AssistantSessionDriverDelegationTest`. Expected: FAIL on cost (0.06510… vs 0.03579).

- [ ] **Step 3: Implement**

In `AssistantSession`, replace the summing of `totalCostUsd` with a maximum. If the field is a `DoubleAdder`,
replace it with `private final AtomicReference<Double> totalCostUsd = new AtomicReference<>(0.0);` (or a
`volatile double` under the existing lock) and update the getter. In `accumulateCost`:

```java
        double turnCost = event.data().path("costUsd").asDouble(0);
        totalCostUsd.accumulateAndGet(turnCost, Math::max);
```

(Use `accumulateAndGet` with `AtomicReference<Double>`: `totalCostUsd.accumulateAndGet(turnCost,
(a, b) -> Math.max(a, b))`.)

Leave the token, duration and turn-count accumulation unchanged. Add a Javadoc/comment explaining that
`turn_complete.costUsd` is the cumulative session cost (Claude's `total_cost_usd`; OpenCode's normalizer
matches it).

- [ ] **Step 4: Run to verify pass**

Run `-Dtest='AssistantSession*Test,OpenCode*Test'`. Then run the full `mvn -q -pl app test`.

- [ ] **Step 5: Commit**

`fix(assistant): record cumulative session cost instead of summing it (#374)`

- [ ] **Step 6: Manual end-to-end (human)**

1. Start a Configuration Assistant session on `opencode` with a paid model and ask two questions. The header shows
   a non-zero cost that grows, and hovering it shows token counts.
2. End the session. The AI usage / cost dashboard shows one entry with the session's final cost, not the sum of
   cumulative values.
3. Repeat step 2 with a Claude session. The recorded cost now matches the header's final cost.

---

## Self-Review Notes

- Issue #374 acceptance (header and usage records non-zero) → Tasks 1 and 2. The duration is added in Task 1.
- The pre-existing Claude over-count is fixed as part of Task 2. It's the same recorded-usage path, and it was
  verified with real `claude` output.
- Names are consistent: `turnUsage`, `settledMessages`, `sessionCostUsd`, and the existing `turn_complete` field
  names.
