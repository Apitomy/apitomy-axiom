# Subagent Activity Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface real-time subagent activity in a side panel during AI Assistant sessions so
users can see what each Agent is doing, how far along it is, and when it finishes.

**Architecture:** The backend parser (`AssistantEventParser`) gains four new SSE event types for
subagent lifecycle (`subagent_started`, `subagent_progress`, `subagent_status`,
`subagent_completed`) parsed from Claude Code's `system` NDJSON events. Subagent-scoped
`assistant`/`user` events (identified by a non-null `parent_tool_use_id` field) are suppressed at
the parser level to keep the main chat timeline clean. The frontend adds a right-side panel
(`AssistantSubagentPanel`) that renders one card per active/completed subagent, built entirely
from these lifecycle events.

**Tech Stack:** Java 25 / Quarkus (backend), React 19 / TypeScript / PatternFly 6 (frontend)

**Spec:** GitHub issue
[#198](https://github.com/Apitomy/apitomy-axiom/issues/198) + design conversation in this
session.

## Global Constraints

- **API-first development** does NOT apply here — no REST API changes needed. All new events flow
  through the existing SSE pipeline.
- **Do not run tests or Maven builds.** The user handles compilation and test execution. Write
  tests but do not execute them.
- **Java style:** 4-space indentation, Javadoc on public methods, explicit types.
- **Frontend style:** Follow existing PatternFly 6 patterns. Use CSS custom properties for
  theming (see existing `.css` files for conventions).
- **Commit messages:** Do not include any Claude attribution.

## Claude Code Subagent Event Format (reference)

When Claude Code spawns a subagent via the `Agent` tool, it emits these events on the same
NDJSON stream:

**Lifecycle events (`type: "system"`):**

| Subtype | Key fields | Meaning |
|---------|-----------|---------|
| `task_started` | `tool_use_id`, `task_id`, `description`, `subagent_type`, `task_type` | Subagent spawned |
| `task_progress` | `tool_use_id`, `task_id`, `description`, `last_tool_name`, `usage.tool_uses`, `usage.duration_ms` | Tool completed within subagent |
| `task_updated` | `task_id`, `patch.status` | Status change (e.g. `"completed"`) |
| `task_notification` | `tool_use_id`, `task_id`, `status`, `summary` | Subagent finished with summary |

**Scoped activity events (`type: "assistant"` / `type: "user"`):**

These carry a `parent_tool_use_id` field matching the Agent's `tool_use_id`. They represent the
subagent's internal tool calls and results. We suppress them at the parser level because
`task_progress` events provide better human-readable descriptions of the same activity.

---

### Task 1: Backend — Parse subagent events in AssistantEventParser

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantEventParser.java`
- Modify: `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantEventParserTest.java`

**Interfaces:**
- Consumes: Claude Code NDJSON events (see format reference above)
- Produces: New `SseEvent` types consumed by the frontend's `processEvent()` in Task 2:
  - `subagent_started` — data: `{ toolUseId: string, taskId: string, description: string, subagentType: string }`
  - `subagent_progress` — data: `{ toolUseId: string, taskId: string, description: string, lastToolName: string, toolCount: int, durationMs: long }`
  - `subagent_status` — data: `{ taskId: string, status: string }`
  - `subagent_completed` — data: `{ toolUseId: string, taskId: string, status: string, summary: string }`

- [ ] **Step 1: Write tests for subagent lifecycle events**

Add these tests to `AssistantEventParserTest.java` after the `// ── Tool progress events` section:

```java
// ── Subagent lifecycle events ─────────────────────────────────────

@Test
void parseSystemTaskStartedEvent() {
    String line = """
            {"type":"system","subtype":"task_started","task_id":"task-1",\
            "tool_use_id":"tu-agent-1","description":"Explore codebase",\
            "subagent_type":"Explore","task_type":"local_agent"}""";

    List<SseEvent> events = parser.parse(line);

    assertEquals(1, events.size());
    SseEvent event = events.get(0);
    assertEquals("subagent_started", event.type());
    assertEquals("tu-agent-1", event.data().path("toolUseId").asText());
    assertEquals("task-1", event.data().path("taskId").asText());
    assertEquals("Explore codebase", event.data().path("description").asText());
    assertEquals("Explore", event.data().path("subagentType").asText());
}

@Test
void parseSystemTaskProgressEvent() {
    String line = """
            {"type":"system","subtype":"task_progress","task_id":"task-1",\
            "tool_use_id":"tu-agent-1","description":"Reading src/Main.java",\
            "subagent_type":"Explore","last_tool_name":"Read",\
            "usage":{"total_tokens":15000,"tool_uses":5,"duration_ms":8000}}""";

    List<SseEvent> events = parser.parse(line);

    assertEquals(1, events.size());
    SseEvent event = events.get(0);
    assertEquals("subagent_progress", event.type());
    assertEquals("tu-agent-1", event.data().path("toolUseId").asText());
    assertEquals("Reading src/Main.java", event.data().path("description").asText());
    assertEquals("Read", event.data().path("lastToolName").asText());
    assertEquals(5, event.data().path("toolCount").asInt());
    assertEquals(8000, event.data().path("durationMs").asLong());
}

@Test
void parseSystemTaskUpdatedCompletedEvent() {
    String line = """
            {"type":"system","subtype":"task_updated","task_id":"task-1",\
            "patch":{"status":"completed","end_time":1700000000000}}""";

    List<SseEvent> events = parser.parse(line);

    assertEquals(1, events.size());
    SseEvent event = events.get(0);
    assertEquals("subagent_status", event.type());
    assertEquals("task-1", event.data().path("taskId").asText());
    assertEquals("completed", event.data().path("status").asText());
}

@Test
void parseSystemTaskUpdatedNoStatusIsIgnored() {
    String line = """
            {"type":"system","subtype":"task_updated","task_id":"task-1","patch":{}}""";

    List<SseEvent> events = parser.parse(line);
    assertTrue(events.isEmpty());
}

@Test
void parseSystemTaskNotificationEvent() {
    String line = """
            {"type":"system","subtype":"task_notification","task_id":"task-1",\
            "tool_use_id":"tu-agent-1","status":"completed",\
            "summary":"Found 5 relevant files."}""";

    List<SseEvent> events = parser.parse(line);

    assertEquals(1, events.size());
    SseEvent event = events.get(0);
    assertEquals("subagent_completed", event.type());
    assertEquals("tu-agent-1", event.data().path("toolUseId").asText());
    assertEquals("completed", event.data().path("status").asText());
    assertEquals("Found 5 relevant files.", event.data().path("summary").asText());
}
```

- [ ] **Step 2: Write tests for subagent event suppression**

Add these tests after the lifecycle tests:

```java
// ── Subagent event suppression ────────────────────────────────────

@Test
void parseAssistantWithParentToolUseIdIsIgnored() {
    String line = """
            {"type":"assistant","parent_tool_use_id":"tu-agent-1",\
            "message":{"content":[{"type":"tool_use","id":"tu-sub-1",\
            "name":"Read","input":{"file_path":"src/Main.java"}}]}}""";

    List<SseEvent> events = parser.parse(line);
    assertTrue(events.isEmpty());
}

@Test
void parseAssistantWithNullParentToolUseIdIsProcessed() {
    String line = """
            {"type":"assistant","parent_tool_use_id":null,\
            "message":{"content":[{"type":"text","text":"Hello"}]}}""";

    List<SseEvent> events = parser.parse(line);
    assertEquals(1, events.size());
    assertEquals("assistant_text", events.get(0).type());
}

@Test
void parseUserWithParentToolUseIdIsIgnored() {
    String line = """
            {"type":"user","parent_tool_use_id":"tu-agent-1",\
            "message":{"content":[{"type":"tool_result",\
            "tool_use_id":"tu-sub-1","content":"result"}]}}""";

    List<SseEvent> events = parser.parse(line);
    assertTrue(events.isEmpty());
}
```

- [ ] **Step 3: Implement `parseSystem()` with subagent subtypes**

Replace the existing `parseSystem()` method in `AssistantEventParser.java` (lines 76–94)
with a switch expression that handles `init` (existing) plus the four new subtypes:

```java
/**
 * Parses system events: session init and subagent lifecycle.
 *
 * @param root the raw NDJSON node
 * @return normalised SSE events for the frontend
 */
private List<SseEvent> parseSystem(JsonNode root) {
    String subtype = root.path("subtype").asText("");
    return switch (subtype) {
        case "init" -> {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("sessionId", root.path("session_id").asText());
            data.put("cwd", root.path("cwd").asText());
            data.put("model", root.path("model").asText());
            JsonNode slashCommands = root.path("slash_commands");
            if (slashCommands.isArray()) {
                data.set("slashCommands", slashCommands);
            }
            JsonNode tools = root.path("tools");
            if (tools.isArray()) {
                data.set("tools", tools);
            }
            yield List.of(new SseEvent("session_init", data));
        }
        case "task_started" -> {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("toolUseId", root.path("tool_use_id").asText());
            data.put("taskId", root.path("task_id").asText());
            data.put("description", root.path("description").asText());
            data.put("subagentType", root.path("subagent_type").asText());
            yield List.of(new SseEvent("subagent_started", data));
        }
        case "task_progress" -> {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("toolUseId", root.path("tool_use_id").asText());
            data.put("taskId", root.path("task_id").asText());
            data.put("description", root.path("description").asText());
            data.put("lastToolName", root.path("last_tool_name").asText());
            JsonNode usage = root.path("usage");
            data.put("toolCount", usage.path("tool_uses").asInt(0));
            data.put("durationMs", usage.path("duration_ms").asLong(0));
            yield List.of(new SseEvent("subagent_progress", data));
        }
        case "task_updated" -> {
            String status = root.path("patch").path("status").asText("");
            if (status.isEmpty()) {
                yield Collections.emptyList();
            }
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("taskId", root.path("task_id").asText());
            data.put("status", status);
            yield List.of(new SseEvent("subagent_status", data));
        }
        case "task_notification" -> {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("toolUseId", root.path("tool_use_id").asText());
            data.put("taskId", root.path("task_id").asText());
            data.put("status", root.path("status").asText());
            data.put("summary", root.path("summary").asText());
            yield List.of(new SseEvent("subagent_completed", data));
        }
        default -> Collections.emptyList();
    };
}
```

- [ ] **Step 4: Add subagent suppression guards to `parseAssistant()` and `parseUser()`**

Add a guard at the top of each method. In `parseAssistant()` (line 96), add before the
`content` extraction:

```java
private List<SseEvent> parseAssistant(JsonNode root) {
    String parentToolUseId = root.path("parent_tool_use_id").asText("");
    if (!parentToolUseId.isEmpty()) {
        return Collections.emptyList();
    }
    JsonNode content = root.path("message").path("content");
    // ... rest unchanged ...
}
```

In `parseUser()` (line 126), add before the `tool_use_result` check:

```java
private List<SseEvent> parseUser(JsonNode root) {
    String parentToolUseId = root.path("parent_tool_use_id").asText("");
    if (!parentToolUseId.isEmpty()) {
        return Collections.emptyList();
    }
    JsonNode toolResult = root.path("tool_use_result");
    // ... rest unchanged ...
}
```

- [ ] **Step 5: Update the class Javadoc**

Add the new event types to the Javadoc event mapping list at the top of
`AssistantEventParser.java` (lines 22–29):

```java
 *   <li>{@code system} (subtype task_started) → {@code subagent_started}</li>
 *   <li>{@code system} (subtype task_progress) → {@code subagent_progress}</li>
 *   <li>{@code system} (subtype task_updated) → {@code subagent_status}</li>
 *   <li>{@code system} (subtype task_notification) → {@code subagent_completed}</li>
 *   <li>{@code assistant} with non-null {@code parent_tool_use_id} → suppressed</li>
 *   <li>{@code user} with non-null {@code parent_tool_use_id} → suppressed</li>
```

Also update the `SseEvent` record Javadoc (line 225) to list the new types.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/AssistantEventParser.java \
       app/src/test/java/io/apitomy/axiom/app/assistant/AssistantEventParserTest.java
git commit -m "feat: parse subagent lifecycle events and suppress scoped events (#198)"
```

---

### Task 2: Frontend — Subagent panel with activity cards

**Files:**
- Create: `ui/src/components/assistant/AssistantSubagentCard.tsx`
- Create: `ui/src/components/assistant/AssistantSubagentCard.css`
- Create: `ui/src/components/assistant/AssistantSubagentPanel.tsx`
- Create: `ui/src/components/assistant/AssistantSubagentPanel.css`
- Modify: `ui/src/components/assistant/AssistantChatPanel.tsx`

**Interfaces:**
- Consumes: SSE event types from Task 1 (`subagent_started`, `subagent_progress`,
  `subagent_status`, `subagent_completed`)
- Produces: Visual subagent panel; `SubagentCardData` interface and dismiss callbacks consumed
  by Task 3 for cross-linking

- [ ] **Step 1: Create `AssistantSubagentCard.tsx` with data interfaces and component**

Create `ui/src/components/assistant/AssistantSubagentCard.tsx`:

```tsx
import { useState } from "react";
import {
    Button,
    ExpandableSection,
    Label,
    Spinner,
} from "@patternfly/react-core";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import "./AssistantSubagentCard.css";

export interface SubagentActivityEntry {
    id: string;
    toolName: string;
    description: string;
}

export interface SubagentCardData {
    id: string;
    taskId: string;
    description: string;
    subagentType: string;
    status: "running" | "completed";
    currentActivity?: string;
    lastToolName?: string;
    toolCount: number;
    durationMs: number;
    summary?: string;
    activityLog: SubagentActivityEntry[];
    dismissed: boolean;
}

interface AssistantSubagentCardProps {
    card: SubagentCardData;
    onDismiss: (id: string) => void;
    onNavigateToAgent?: (toolUseId: string) => void;
    highlighted?: boolean;
}

export function AssistantSubagentCard({
    card, onDismiss, onNavigateToAgent, highlighted,
}: AssistantSubagentCardProps) {
    const [isExpanded, setIsExpanded] = useState(false);
    const isComplete = card.status === "completed";

    return (
        <div
            className={`axiom-subagent-card${highlighted ? " axiom-subagent-card--highlighted" : ""}`}
            data-status={card.status}
        >
            <div className="axiom-subagent-card__header">
                <div className="axiom-subagent-card__title-row">
                    <Label isCompact color="orange">{card.subagentType}</Label>
                    <span className="axiom-subagent-card__description">
                        {card.description}
                    </span>
                </div>
                {isComplete && (
                    <Button
                        variant="plain"
                        size="sm"
                        aria-label="Dismiss"
                        className="axiom-subagent-card__close-btn"
                        onClick={() => onDismiss(card.id)}
                    >
                        <TimesIcon />
                    </Button>
                )}
            </div>

            <div className="axiom-subagent-card__status">
                {isComplete ? (
                    <span className="axiom-subagent-card__completed">
                        <CheckCircleIcon />
                        Completed in {formatDuration(card.durationMs)}
                        {" · "}{card.toolCount} tools used
                    </span>
                ) : (
                    <span className="axiom-subagent-card__running">
                        <Spinner size="sm" />
                        <span className="axiom-subagent-card__activity">
                            {card.currentActivity || "Starting..."}
                        </span>
                        <span className="axiom-subagent-card__stats">
                            {formatDuration(card.durationMs)} · {card.toolCount} tools
                        </span>
                    </span>
                )}
            </div>

            {card.activityLog.length > 0 && (
                <ExpandableSection
                    toggleText={isExpanded
                        ? "Hide activity"
                        : `Show activity (${card.activityLog.length})`}
                    isExpanded={isExpanded}
                    onToggle={(_e, expanded) => setIsExpanded(expanded)}
                    isIndented
                    className="axiom-subagent-card__activity-section"
                >
                    <div className="axiom-subagent-card__activity-log">
                        {card.activityLog.map((entry) => (
                            <div key={entry.id} className="axiom-subagent-card__activity-entry">
                                <Label isCompact color="blue"
                                    className="axiom-subagent-card__tool-badge">
                                    {entry.toolName}
                                </Label>
                                <span className="axiom-subagent-card__activity-desc">
                                    {entry.description}
                                </span>
                            </div>
                        ))}
                    </div>
                </ExpandableSection>
            )}

            {onNavigateToAgent && (
                <Button
                    variant="link"
                    size="sm"
                    className="axiom-subagent-card__navigate"
                    onClick={() => onNavigateToAgent(card.id)}
                >
                    Show in conversation
                </Button>
            )}
        </div>
    );
}

function formatDuration(ms: number): string {
    const seconds = Math.round(ms / 1000);
    if (seconds < 60) return `${seconds}s`;
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return secs > 0 ? `${mins}m ${secs}s` : `${mins}m`;
}
```

- [ ] **Step 2: Create `AssistantSubagentCard.css`**

Create `ui/src/components/assistant/AssistantSubagentCard.css`. Key classes:

- `.axiom-subagent-card` — bordered card with `border-radius: 6px`, `margin-bottom: 8px`
- `.axiom-subagent-card--highlighted` — brand-blue border + box-shadow with a 2s fade-out
  animation
- `.axiom-subagent-card__header` — flex row with secondary background, holds type badge +
  description + close button
- `.axiom-subagent-card__title-row` — flex with gap, `min-width: 0` for text truncation
- `.axiom-subagent-card__description` — 13px, `text-overflow: ellipsis`
- `.axiom-subagent-card__close-btn` — `flex-shrink: 0`, small padding
- `.axiom-subagent-card__status` — 12px text, secondary background, shows
  running (spinner + activity text + stats) or completed (check icon + summary)
- `.axiom-subagent-card__completed` — success green, flex with gap
- `.axiom-subagent-card__running` — subtle grey, flex with gap
- `.axiom-subagent-card__activity` — `flex: 1`, ellipsis overflow
- `.axiom-subagent-card__stats` — mono font, 11px, `flex-shrink: 0`
- `.axiom-subagent-card__activity-log` — `max-height: 200px`, `overflow-y: auto`
- `.axiom-subagent-card__activity-entry` — flex row, 3px vertical padding
- `.axiom-subagent-card__activity-desc` — subtle grey, ellipsis overflow
- `.axiom-subagent-card__navigate` — link button, 12px, bottom of card

Follow the existing CSS variable conventions from `AssistantToolUseBlock.css` — use
`var(--pf-t--global--...)` tokens for all colors and fonts.

- [ ] **Step 3: Create `AssistantSubagentPanel.tsx`**

Create `ui/src/components/assistant/AssistantSubagentPanel.tsx`:

```tsx
import { Button } from "@patternfly/react-core";
import {
    AssistantSubagentCard,
    type SubagentCardData,
} from "./AssistantSubagentCard";
import "./AssistantSubagentPanel.css";

interface AssistantSubagentPanelProps {
    cards: SubagentCardData[];
    onDismiss: (id: string) => void;
    onDismissAllCompleted: () => void;
    onNavigateToAgent?: (toolUseId: string) => void;
    highlightedCardId?: string;
}

export function AssistantSubagentPanel({
    cards, onDismiss, onDismissAllCompleted, onNavigateToAgent, highlightedCardId,
}: AssistantSubagentPanelProps) {
    const hasCompleted = cards.some((c) => c.status === "completed");

    return (
        <div className="axiom-subagent-panel">
            <div className="axiom-subagent-panel__header">
                <span className="axiom-subagent-panel__title">Subagents</span>
                {hasCompleted && (
                    <Button variant="link" size="sm" onClick={onDismissAllCompleted}>
                        Close All
                    </Button>
                )}
            </div>
            <div className="axiom-subagent-panel__cards">
                {cards.map((card) => (
                    <AssistantSubagentCard
                        key={card.id}
                        card={card}
                        onDismiss={onDismiss}
                        onNavigateToAgent={onNavigateToAgent}
                        highlighted={highlightedCardId === card.id}
                    />
                ))}
            </div>
        </div>
    );
}
```

- [ ] **Step 4: Create `AssistantSubagentPanel.css`**

Create `ui/src/components/assistant/AssistantSubagentPanel.css`:

```css
.axiom-subagent-panel {
    width: 320px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    border-left: 1px solid var(--pf-t--global--border--color--default, #d2d2d2);
    background-color: var(--pf-t--global--background--color--primary--default, #fff);
}

.axiom-subagent-panel__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px;
    border-bottom: 1px solid var(--pf-t--global--border--color--default, #d2d2d2);
    flex-shrink: 0;
}

.axiom-subagent-panel__title {
    font-weight: 600;
    font-size: 14px;
}

.axiom-subagent-panel__cards {
    flex: 1 1 0;
    overflow-y: auto;
    padding: 8px;
}
```

- [ ] **Step 5: Add subagent state and event routing to `AssistantChatPanel.tsx`**

In `AssistantChatPanel.tsx`, make these changes:

**5a — Imports.** Add at top:
```tsx
import { AssistantSubagentPanel } from "./AssistantSubagentPanel";
import type { SubagentCardData, SubagentActivityEntry } from "./AssistantSubagentCard";
```

**5b — State.** Add after the existing `useState` declarations (line 31):
```tsx
const [subagentCards, setSubagentCards] = useState<Map<string, SubagentCardData>>(new Map());
```

**5c — Derived values.** Add before the `return` statement (before line 403):
```tsx
const visibleCards = Array.from(subagentCards.values()).filter(c => !c.dismissed);
```

**5d — Dismiss handlers.** Add after `handleCreateAutoApproval` (after line 401):
```tsx
const handleDismissSubagent = useCallback((id: string) => {
    setSubagentCards((prev) => {
        const card = prev.get(id);
        if (!card) return prev;
        const next = new Map(prev);
        next.set(id, { ...card, dismissed: true });
        return next;
    });
}, []);

const handleDismissAllCompleted = useCallback(() => {
    setSubagentCards((prev) => {
        const next = new Map(prev);
        for (const [id, card] of next) {
            if (card.status === "completed") {
                next.set(id, { ...card, dismissed: true });
            }
        }
        return next;
    });
}, []);
```

**5e — Event handlers.** Add these cases to the `processEvent` switch statement, before the
`default` / closing brace (before `"unhandled_event"` at line 223):

```tsx
case "subagent_started":
    setSubagentCards((prev) => {
        const next = new Map(prev);
        next.set(data.toolUseId as string, {
            id: data.toolUseId as string,
            taskId: data.taskId as string,
            description: data.description as string,
            subagentType: data.subagentType as string,
            status: "running",
            toolCount: 0,
            durationMs: 0,
            activityLog: [],
            dismissed: false,
        });
        return next;
    });
    break;

case "subagent_progress":
    setSubagentCards((prev) => {
        const card = prev.get(data.toolUseId as string);
        if (!card) return prev;
        const entry: SubagentActivityEntry = {
            id: String(++messageIdCounter),
            toolName: data.lastToolName as string,
            description: data.description as string,
        };
        const next = new Map(prev);
        next.set(card.id, {
            ...card,
            currentActivity: data.description as string,
            lastToolName: data.lastToolName as string,
            toolCount: data.toolCount as number,
            durationMs: data.durationMs as number,
            activityLog: [...card.activityLog, entry],
        });
        return next;
    });
    break;

case "subagent_status":
    setSubagentCards((prev) => {
        for (const [id, card] of prev) {
            if (card.taskId === (data.taskId as string)) {
                const next = new Map(prev);
                next.set(id, {
                    ...card,
                    status: (data.status as string) === "completed"
                        ? "completed" : card.status,
                });
                return next;
            }
        }
        return prev;
    });
    break;

case "subagent_completed":
    setSubagentCards((prev) => {
        const card = prev.get(data.toolUseId as string);
        if (!card) return prev;
        const next = new Map(prev);
        next.set(card.id, {
            ...card,
            status: "completed",
            summary: data.summary as string,
        });
        return next;
    });
    break;
```

**5f — Clear subagent cards on conversation reset.** In the existing `conversation_reset` case
(line 200), add after `setMessages(...)`:

```tsx
setSubagentCards(new Map());
```

**5g — Layout.** Replace the JSX `return` block (lines 403–419) with a flex row that
conditionally includes the panel:

```tsx
return (
    <div style={{
        display: "flex",
        flex: "1 1 0",
        minHeight: 0,
    }}>
        <div style={{
            display: "flex",
            flexDirection: "column",
            flex: "1 1 0",
            minWidth: 0,
            minHeight: 0,
        }}>
            <AssistantMessageList
                messages={messages}
                onPermissionRespond={handlePermissionRespond}
                onCreateAutoApproval={handleCreateAutoApproval}
                isProcessing={isProcessing}
                processingText={processingText}
            />
            <AssistantMessageInput
                onSend={handleSend}
                disabled={isProcessing}
                slashCommands={slashCommands}
            />
        </div>
        {visibleCards.length > 0 && (
            <AssistantSubagentPanel
                cards={visibleCards}
                onDismiss={handleDismissSubagent}
                onDismissAllCompleted={handleDismissAllCompleted}
            />
        )}
    </div>
);
```

- [ ] **Step 6: Commit**

```bash
git add ui/src/components/assistant/AssistantSubagentCard.tsx \
       ui/src/components/assistant/AssistantSubagentCard.css \
       ui/src/components/assistant/AssistantSubagentPanel.tsx \
       ui/src/components/assistant/AssistantSubagentPanel.css \
       ui/src/components/assistant/AssistantChatPanel.tsx
git commit -m "feat: add subagent activity panel with live status cards (#198)"
```

---

### Task 3: Frontend — Cross-linking between Agent blocks and panel cards

**Files:**
- Modify: `ui/src/components/assistant/AssistantMessageList.tsx`
- Modify: `ui/src/components/assistant/AssistantToolUseBlock.tsx`
- Modify: `ui/src/components/assistant/AssistantToolUseBlock.css`
- Modify: `ui/src/components/assistant/AssistantChatPanel.tsx`

**Interfaces:**
- Consumes: `SubagentCardData` from Task 2, existing `ChatMessage` and
  `AssistantToolUseBlock` props
- Produces: Bidirectional navigation — clicking an Agent tool_use block in the chat scrolls
  to its card in the panel; clicking a panel card scrolls to its Agent block in the chat

- [ ] **Step 1: Add `data-tool-use-id` attribute to tool_use blocks in `AssistantMessageList.tsx`**

Wrap the `AssistantToolUseBlock` in a `div` with a data attribute. In
`AssistantMessageList.tsx`, replace the `case "tool_use"` block (lines 167–182) with:

```tsx
case "tool_use":
    return (
        <div key={msg.id} data-tool-use-id={msg.toolUseId}>
            <AssistantToolUseBlock
                toolName={msg.toolName || "unknown"}
                toolUseId={msg.toolUseId}
                input={msg.toolInput}
                result={msg.toolResult}
                isError={msg.isError}
                elapsedSeconds={msg.elapsedSeconds}
                permissionId={msg.permissionId}
                permissionResolved={msg.permissionResolved}
                permissionAllowed={msg.permissionAllowed}
                onPermissionRespond={onPermissionRespond}
                onCreateAutoApproval={onCreateAutoApproval}
                onSubagentClick={onSubagentClick}
                highlighted={msg.toolUseId === highlightedAgentBlockId}
            />
        </div>
    );
```

- [ ] **Step 2: Add new props to `AssistantMessageList`**

Add to `AssistantMessageListProps` (line 73) and destructure in the component:

```tsx
interface AssistantMessageListProps {
    messages: ChatMessage[];
    onPermissionRespond: (permissionId: string, allow: boolean,
        toolInput?: Record<string, unknown>) => void;
    onCreateAutoApproval?: (toolName: string, fieldName: string | undefined,
        pattern: string | undefined, permissionId: string) => void;
    isProcessing?: boolean;
    processingText?: string;
    onSubagentClick?: (toolUseId: string) => void;
    highlightedAgentBlockId?: string;
}
```

Destructure the two new props in the component function signature (line 82).

- [ ] **Step 3: Add `onSubagentClick` and `highlighted` props to `AssistantToolUseBlock`**

In `AssistantToolUseBlock.tsx`, add to the props interface (line 41):

```tsx
onSubagentClick?: (toolUseId: string) => void;
highlighted?: boolean;
```

Destructure in the component (line 55). Add `toolUseId` to the props interface too — it's
needed for the click callback:

```tsx
toolUseId?: string;
```

- [ ] **Step 4: Make Agent tool_use labels clickable**

In `AssistantToolUseBlock.tsx`, modify the `Label` inside `toggleContent` (line 86). When
`toolName === "Agent"` and `onSubagentClick` is provided, make the label clickable:

```tsx
<Label
    isCompact
    color={isError ? "red" : getToolColor(toolName)}
    onClick={toolName === "Agent" && onSubagentClick && toolUseId
        ? (e) => { e.stopPropagation(); onSubagentClick(toolUseId); }
        : undefined}
    style={toolName === "Agent" && onSubagentClick ? { cursor: "pointer" } : undefined}
>
    {toolName}
</Label>
```

- [ ] **Step 5: Add highlight styling for Agent blocks**

Add a `data-highlighted` attribute to the outer `div` in `AssistantToolUseBlock.tsx`
(line 81):

```tsx
<div className="axiom-tool-use" data-border={borderVariant || undefined}
    data-highlighted={highlighted || undefined}>
```

Add CSS to `AssistantToolUseBlock.css`:

```css
.axiom-tool-use[data-highlighted] {
    border: 2px solid var(--pf-t--global--color--brand--default, #0066cc);
    animation: axiom-tool-highlight 2s ease-out forwards;
}

@keyframes axiom-tool-highlight {
    0% { box-shadow: 0 0 0 3px rgba(0, 102, 204, 0.3); }
    100% { box-shadow: none; }
}
```

- [ ] **Step 6: Wire up cross-linking in `AssistantChatPanel.tsx`**

Add state and handlers after the dismiss handlers (from Task 2):

```tsx
const [highlightedCardId, setHighlightedCardId] = useState<string | null>(null);
const [highlightedAgentBlockId, setHighlightedAgentBlockId] = useState<string | null>(null);

const handleSubagentClick = useCallback((toolUseId: string) => {
    setHighlightedCardId(toolUseId);
    setTimeout(() => setHighlightedCardId(null), 2000);
}, []);

const handleNavigateToAgent = useCallback((toolUseId: string) => {
    const el = document.querySelector(`[data-tool-use-id="${CSS.escape(toolUseId)}"]`);
    if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "center" });
    }
    setHighlightedAgentBlockId(toolUseId);
    setTimeout(() => setHighlightedAgentBlockId(null), 2000);
}, []);
```

Pass to `AssistantMessageList`:

```tsx
<AssistantMessageList
    messages={messages}
    onPermissionRespond={handlePermissionRespond}
    onCreateAutoApproval={handleCreateAutoApproval}
    isProcessing={isProcessing}
    processingText={processingText}
    onSubagentClick={handleSubagentClick}
    highlightedAgentBlockId={highlightedAgentBlockId}
/>
```

Pass to `AssistantSubagentPanel`:

```tsx
<AssistantSubagentPanel
    cards={visibleCards}
    onDismiss={handleDismissSubagent}
    onDismissAllCompleted={handleDismissAllCompleted}
    onNavigateToAgent={handleNavigateToAgent}
    highlightedCardId={highlightedCardId}
/>
```

Also pass `toolUseId` to `AssistantToolUseBlock` in `AssistantMessageList.tsx` — add
`toolUseId={msg.toolUseId}` to the props in the `case "tool_use"` block.

- [ ] **Step 7: Commit**

```bash
git add ui/src/components/assistant/AssistantMessageList.tsx \
       ui/src/components/assistant/AssistantToolUseBlock.tsx \
       ui/src/components/assistant/AssistantToolUseBlock.css \
       ui/src/components/assistant/AssistantChatPanel.tsx
git commit -m "feat: add cross-linking between Agent blocks and subagent panel cards (#198)"
```
