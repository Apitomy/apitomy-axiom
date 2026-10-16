# Workflow Workbench

The Workflow Workbench is a place to build a workflow together with the action types and tools it uses, with
an AI assistant working beside you. You edit drafts. Nothing you or the AI change in the workbench affects the
running system until you publish it.

Open it from **Workbench** in the sidebar (after **Events**).

## Compositions

A *composition* groups one workflow with the action types and tools you build for it. Each item in a
composition is a draft with its own revision history.

### The composition list

The **Workbench** page (`/workbench`) lists your compositions with these columns:

| Column  | Meaning                                                            |
|---------|--------------------------------------------------------------------|
| Name    | The composition name.                                              |
| Goal    | The optional goal you entered when creating it.                    |
| State   | `AI working` while the AI assistant is running a turn, else `idle`. |
| Updated | When the composition last changed.                                 |

Click a row to open the composition. Use the trash icon on a row to delete a composition (you are asked to
confirm).

### Creating a composition

1. Click **New composition**.
2. Enter a **Name** (required) and, optionally, a **Goal** that describes what the workflow should do.
3. Click **Create**.

The composition is created with a blank workflow draft that has the same name, and the workbench opens.

### Drafts and published items

Every item in a composition is a draft. A draft can be new, or a copy of an existing published action type or
tool that you added to the composition. Drafts with changes that are not yet published are shown in blue in
the Components tree (hover text "Unpublished changes"). Use **Publish…** to make drafts live (see
[Publishing](#publishing)).

## Layout

The workbench page (`/workbench/:id`) has a toolbar and three panes:

- **Components** (left): the composition tree.
- **Centre**: tabs with the workflow canvas and the action type and tool editors, and a bottom panel with the
  **Tests** and **Runs** tabs.
- **AI Assistant** (right): the chat with the AI.

### Toolbar

| Control                                      | What it does                                                                                     |
|----------------------------------------------|--------------------------------------------------------------------------------------------------|
| Breadcrumb (**Workbench** / name)            | Goes back to the composition list.                                                               |
| **Apply live** / **Review changes**          | How AI workflow changes reach the canvas (see [Working with the AI](#working-with-the-ai)). Saved per composition. |
| **Place new nodes** / **Tidy after AI changes** | How the canvas lays out AI changes: place only the new nodes, or tidy the whole layout.      |
| **Maximize canvas** / **Restore side panels** | Collapses (or restores) both side panes.                                                        |
| **Focus** / **Exit focus** (Ctrl+Shift+F)    | Hides the application masthead and sidebar.                                                      |
| **Break out**                                | Opens the composition in a new browser window without the application chrome. The original window shows "Opened in another window" with a **Bring back here** link. |
| **Publish…**                                 | Saves any pending edits and opens the Publish dialog.                                           |

### Resizing and collapsing panes

Drag the splitters between the panes to resize the side panes (160 to 800 pixels). Use the `«` and `»`
buttons to collapse or expand the left pane and the chat pane. While the chat pane is collapsed, a pulsing
marker shows when the AI is working.

The bottom panel is collapsed when you open a composition. Use the arrow button on its header to expand it.
It expands by itself when a test run starts, when the AI suggests a test, or when a new run of the workflow
fails.

Pane widths, collapsed panes, the layout option and the selected bottom tab are stored in the browser.

## Components

The **Components** pane lists the composition's items in four groups: **Workflow**, **Action types**,
**Tools** and **Subscriptions** (see [Subscriptions](#subscriptions)). Click an item to open it in a centre
tab.

### Adding action types and tools

Click the **Create** (plus) button in the pane toolbar and choose **Action Type** or **Tool**. In the dialog,
choose:

- **New**: enter a **Name**. For a new action type, also choose the **Execution mode**:
  - **AI Agent** — executed by an AI agent.
  - **Node.js** — runs a JavaScript module.
  - **Script** — executes a bash script.
- **Existing**: pick a published action type or tool. A draft copy of it is added to the composition.

Click **Add**. The new item opens in a tab.

You can also add an action type from the workflow canvas: when you open an action type referenced by a node,
the workbench opens its draft, or adds it to the composition (as a copy of the published action type if one
exists, otherwise as a new draft).

### Row menu

Each action type and tool row has a menu (three dots) with:

- **Test…** — opens the test dialog for the item (see [Testing](#testing)).
- **Ask AI about this…** — pre-fills the chat with a question about the item. Disabled while the AI is
  working.
- **Remove** — removes the draft from the composition after confirmation. Its draft and revision history are
  discarded; a published item with the same name is not deleted. If the item is still used, the dialog lists
  where. Disabled while the AI is working.

The workflow has no row menu and cannot be removed.

### Markers

Each row can show:

- A status icon: a green check (valid), a yellow warning (warnings; publishing is still allowed) or a red
  error (errors; fix them before publishing). Hover for the number of problems.
- A lock icon while the AI is editing the item. The item becomes editable when the AI turn ends.
- An **AI** label on items changed by the last AI turn.

### Revision history

Select an item and click **Revision history** (clock icon) in the pane toolbar. The dialog lists the item's
revisions, newest first, with the author (**AI** or **You**), the AI turn id, and the time. The current
revision is marked **Current**. Click **Restore** to make an older revision the current one.

## Editing

All editors save automatically shortly after you stop typing. If an item was changed elsewhere in the
meantime, the editor shows a "Changed elsewhere" warning with a **Reload** link.

### Workflow canvas

The first centre tab is the workflow canvas. It cannot be closed. It is the same Flow editor described in
[Workflows](workflows.md), with these differences:

- Node and edge properties open in a properties dialog: double-click a node or edge.
- Simulate is turned off.
- The right-click menu of nodes, edges and the canvas has **Ask AI about this…**, and the menu of a problem
  has **Ask AI to fix this**. Both pre-fill the chat.
- The canvas validates the workflow against the composition, so action types that exist only as drafts in the
  composition are known to the editor.

### Action type and tool editors

Action types and tools open in their own tabs with the same editors as the **Components** pages (for example
**Info**, **Inputs**, **Outputs**, **Script** or **Code**, **Environment** for action types; **Info**,
**Parameters**, **Script Template** for tools). When an item has problems, its **Problems** tab offers **Ask
AI to fix this** on each problem.

Close an item tab with its close button or by middle-clicking the tab.

## Working with the AI

The **AI Assistant** pane is a chat with an AI that works on the same drafts as you. The pane title shows a
badge with the engine and model in use (for example `OpenCode · default`); hover over it for details. If the
assistant cannot start, the pane shows "Assistant unavailable" with a **Retry** link.

When you send a message, the workbench first saves your pending workflow edits and tells the AI which nodes
and edges are selected on the canvas. While the AI is working, the chat input is disabled and the items it
edits are locked for you. Clearing the conversation (`/clear`) is not supported in the workbench.

### Applying AI workflow changes

The AI changes the workflow by proposing change sets. They are applied by your canvas when the AI turn ends:

- **Apply live**: the changes are applied to the canvas directly.
- **Review changes**: the changes are shown on the canvas as a proposal that you accept or reject.

If a change set cannot be applied (for example because the workflow changed in the meantime), the canvas
shows an error. The AI changes action types and tools directly; those changes appear in their tabs.

### Tool cards

The AI's tool calls are shown as compact cards in the chat, for example "Read the composition", "Created
action type check-pr-status VALID" or "Proposed 1 workflow change". Click a card to open the item it is about,
go to the workflow, or open a suggested test. Expand a card to see the details of the call.

### Asking the AI about something

Several places pre-fill the chat with a prompt that you can edit before sending:

- **Ask AI about this…** in a component row menu or the canvas context menu.
- **Ask AI to fix this** on a problem in the canvas or an item editor.
- **Ask AI to analyze** on a failed workflow run (see [Runs](#runs)).

### Undoing an AI turn

After an AI turn ends, an **Undo AI turn** button appears in the chat toolbar. It restores the items the turn
changed and removes the items it created, then shows a summary (restored, removed, skipped). If you changed
the workflow after that turn, the workflow is left alone.

### What the AI can and cannot do

The AI can:

- Read the composition and its items, and create, update and remove action type, tool and subscription
  drafts.
- Change the workflow, only through change sets.
- Validate the composition.
- Look at recent events in the Event Stream and evaluate expressions against them.
- Preview a subscription filter against the 200 most recent events (`preview_subscription`).
- Read runs of the published workflow and their traces.
- Look up published action types, tools and secret names (never secret values).
- Suggest a test with sample inputs.

The AI cannot publish or enable subscriptions, and it never runs tests or workflows. When it creates or
changes a Node.js or script action type or a tool, it suggests a test that you can run.

## Testing

You can test Node.js and script action types and tools from the workbench. AI Agent action types cannot be
tested yet. Only you run tests.

### Running a test

Start a test in one of these ways:

- Open the item's tab and click **Test…** in the bottom panel header.
- Choose **Test…** in the item's row menu.

The test dialog asks for the item's inputs (for an action type) or parameters (for a tool). Each field shows
its type and description; required fields are marked. Object and array values are entered as JSON. Under
**Subject**, choose a subject to run in its directory, or **None — run in a temporary directory**. For a
tool, the **Environment** section sets the variables for the test run: a tool gets no secrets automatically,
so add the secrets it needs (in a workflow it runs with the environment of the action type that allows it).
An action type uses its own **Environment**. Click **Run**. The workbench remembers the values (and a tool's
environment) you used for each item.

The test uses the current draft, including unsaved edits, which are saved first.

### The Tests tab

The **Tests** tab of the bottom panel lists recent test runs, newest first. Each row shows a status icon
(running, passed, failed, error or suggested), the item name (click it to open the item), the time,
duration, exit code and a one-line summary. Click a row to open the test run details.

### Test run details

The details dialog opens when a run starts and updates when it finishes. It shows any problems, then these
tabs:

- **Script** or **Code**: the resolved script or Node.js code that ran.
- **Inputs** or **Parameters**: the values used, as a list or JSON.
- **Outputs**: the outputs of an action type.
- **Stdout/err**: the standard output and error streams.

### AI-suggested tests

When the AI suggests a test, it appears in the **Tests** tab with a **Suggested by AI** label and a **Run…**
button. **Run…** opens the test dialog pre-filled with the AI's sample values. Review them before you click
**Run**; a test runs the real code against real systems.

### Limits

- Only Node.js and script action types, and tools, can be tested.
- At most 2 test runs per composition can run at the same time.
- Standard output and standard error are each truncated to 64,000 characters.
- Tool test runs time out after 30 seconds.
- The last 50 test runs of each composition are kept in memory. They are lost when the server restarts.

## Runs

The **Runs** tab of the bottom panel lists the runs of the composition's published workflow, newest first.
Until the workflow is published, the tab shows "Publish the workflow to see its runs here." Use **Refresh** to
reload the list.

Each row shows the status, run number, subject (followed by the run's title when it has one), workflow
version, start time and, for failed or waiting runs, the current node. Failed runs have an **Ask AI to analyze** link.

Click a row to open the run details dialog. It shows the status, version, start time, duration and current
node, the failure reason, and these tabs:

- **Graph**: the workflow with the run's progress.
- **Failure** (failed runs only): the failed tasks with their output or log.
- **Context**: the run context as JSON.

For a failed run, **Ask AI to analyze** pre-fills the chat with a request to find the cause and fix the
workflow or its components. **Open run page** opens the run in the Runs page in a new tab.

## Publishing

Click **Publish…** to open the Publish dialog. It lists the items with unpublished changes. Items without
errors are selected by default. When you select an item, the changed drafts it depends on are selected too
(the action types a workflow uses, and the tools an action type uses) and marked "required by".

Click **Publish**. The workbench then:

- Blocks the publish if a selected item has validation errors, or uses an action type or tool that is neither
  published nor selected. The dialog lists the problems per item. Warnings do not block publishing.
- Reports a conflict if a published item changed outside the workbench since it was loaded, if a new item's
  name is already used by a published item, or if an item was renamed (publishing creates a new item and
  keeps the old one). Click **Publish anyway** to publish regardless.
- Otherwise, creates or updates the published workflow, action types, tools and subscriptions, and reports
  how many were created and updated.

Publishing does not delete published items (removed drafts and old names stay published) and does not start
the workflow. Published subscriptions stay disabled until you enable them (see
[Subscriptions](#subscriptions)).

## Subscriptions

A composition can own the subscriptions that drive its workflow, so the workbench takes you from a goal to
working automation. A subscription filters the Event Stream and routes matching events (see
[Concepts](concepts.md#subscriptions)). In a composition, a subscription can:

- **Start runs** of the composition's workflow, with a `create-workflow` rule. The rule must name the
  composition's own workflow (`workflowDefinitionName`).
- **Feed receive-event nodes**, with a `workflow-dispatch` rule, which delivers events to runs waiting at a
  matching `receive-event` node. Without a `workflow-dispatch` subscription, receive-event nodes never receive
  events.

### Creating a subscription

Click the **Create** (plus) button in the **Components** pane toolbar and choose **Subscription**, or ask the
AI to create one. A composition can have any number of subscriptions.

### The subscription editor

The editor has these fields:

- **Name** and **Description**.
- **Filter expression**: the EL expression evaluated against each event. An empty expression matches every
  event.
- **Routing**: the routing rules. **Add start rule** adds a `create-workflow` rule for the composition's
  workflow; **Add dispatch rule** adds a `workflow-dispatch` rule. Each rule has a remove button.

Below the form, the **Matching events** panel evaluates the current filter against the 200 most recent events
and shows how many match (for example "12 of 200 recent events match"), how many events could not be
evaluated together with the first error, and the matching events, each linking to its Event Stream page.

### Validation

On top of the usual subscription checks, the workbench reports:

| Code | Severity | Meaning |
|------|----------|---------|
| `FOREIGN_WORKFLOW_ROUTE` | Error | A `create-workflow` rule does not start this composition's workflow by name (it uses another name or a `workflowDefinitionId`). |
| `ENABLED_IGNORED` | Warning | The draft sets `enabled`. Subscriptions are published disabled; enable them in the workbench after publishing. |
| `OVERLAPPING_SUBSCRIPTION` | Warning | Another enabled published subscription, not part of this composition, already starts this composition's workflow. |

### Publishing and enabling

A subscription needs the composition's workflow to be published already, or selected in the same publish.

Publishing never turns automation on by itself:

- A new subscription (no published subscription with that name exists yet) is published **disabled**.
- Republishing a subscription that this composition already published keeps its enabled state and its
  **Process events from** time.
- If you rename a subscription item that was already published, publishing it creates a new subscription
  (disabled) and **disables** the previously published subscription. The old subscription is kept, not
  deleted; it is still listed on the Events → Subscriptions page, where you can delete it.
- If a forced publish overwrites a same-name subscription that this composition did not publish, the
  subscription is left **disabled** (its **Process events from** time is kept).
- Turning the subscription on or off (here or on the Events → Subscriptions page) does not count as a change
  outside the workbench.

Once a subscription is published, the editor header shows an **Enabled** switch. The switch is disabled (with
the tooltip "Publish the workflow before enabling this subscription") until the composition's workflow is
published. Enabling a subscription sets its **Process events from** time to now, so earlier events are
ignored; only new events start or resume runs. Turning the switch off disables the subscription.

Removing a subscription draft, or deleting the composition, leaves the published subscription as it is
(enabled or not). Disable or delete it on the Events → Subscriptions page.
