# AI Assistant

The AI Assistant is a conversational interface powered by Claude Code that supports
arbitrary tasks through configurable **session templates**. Each template defines a
persona, tools, MCP servers, and working directory — so you can create sessions
tailored to specific workflows, from generating Axiom configuration to general-purpose
coding assistance.

Out of the box, Axiom ships three built-in templates: a **Configuration Assistant** for
creating and updating tools, action types, report definitions, toolsets, and session
templates, a **General Assistant** for open-ended tasks, and a **Project Assistant** for
working within the context of a specific project. You can also create your own templates
to define custom workflows.

!!! note
    The AI Assistant requires **Claude Code** as the AI engine. The `claude` CLI must
    be installed and available on your PATH.

---

## Accessing the Assistant

Click the robot icon in the top-right corner of the Axiom masthead. The icon may pulse
if you have never clicked it before.

This takes you to the **Assistant Sessions** page, where you can create a new session
or resume an existing one.

---

## Session Templates

Session templates define the behavior and capabilities of an AI Assistant session.
Each template specifies:

| Field | Purpose |
|-------|---------|
| **Name / Description** | Display name and summary shown in the template picker. |
| **System Prompt** | Markdown instructions passed to Claude Code via `--append-system-prompt`. |
| **Welcome Message** | First message shown in the chat, attributed to the assistant. |
| **Working Directory** | Absolute path for the session. If empty, Axiom creates a temporary directory under `~/.axiom/assistant/sessions/`. For project-scoped sessions, defaults to the project workspace. |
| **Initial Message** | Optional message automatically sent to the AI when a session starts. Supports `{{projectName}}` placeholder. |
| **Model** | Optional AI model override (e.g., a specific Claude model). |
| **MCP Servers** | Named MCP server configurations to include in the session. |
| **Allowed Tools** | Tool patterns for `--allowedTools` (e.g., `Read(*)`, `Bash(ls *)`). Use `@ToolsetName` to include all tools from a toolset. |
| **Init Script** | Optional startup script executed when the session is created (see [Init Scripts](#init-scripts) below). |
| **Environment** | Optional key-value environment variables injected into the Claude process. Values support `${secret:NAME}` syntax. |

### Built-in Templates

Axiom ships three immutable built-in templates:

- **Configuration Assistant** (`axiom-config-assistant`) — purpose-built for creating
  and updating Axiom configuration items (tools, action types, report definitions,
  toolsets, and session templates) with a two-panel layout and Apply workflow.
- **General Assistant** (`general-assistant`) — a minimal template for open-ended tasks
  with no sidebar or special behavior.
- **Project Assistant** (`project-assistant`) — scoped to a specific Axiom project, with
  the working directory set to the project workspace and access to project-specific MCP
  tools for querying tasks, events, and the discussion thread.

Built-in templates cannot be modified or deleted, but you can clone them to create
editable copies.

### User-Defined Templates

You create and manage custom templates from **Configuration > Session Templates**. Each
template supports all the fields listed above, giving you full control over the
assistant's persona and capabilities.

### Init Scripts

Init scripts run once when a session is created, **before** Claude Code starts. They
execute in the session's working directory and are useful for preparing the environment
— cloning a repository, installing dependencies, fetching data, or writing config files
that the assistant will need during the conversation.

**Script types:**

| Type | Runner | Use when |
|------|--------|----------|
| **Bash** | `/bin/bash` | Shell commands, git operations, file manipulation, package installs |
| **Node.js** | `node` | Fetching data from APIs, generating config files, complex setup logic |

Select the script type from the **Init Script** tab dropdown when editing a template.
Write the script body in the editor below it.

**Timeout:** Init scripts have a **60-second timeout**. If the script does not exit
within 60 seconds, it is killed and the session continues without it. Keep scripts
focused on quick setup tasks — long-running operations should be handled by the
assistant itself during the conversation.

**Environment:** Init scripts inherit the environment variables defined on the template
(including resolved `${secret:NAME}` references), so they can use credentials for git
clones, API calls, or authenticated downloads.

**Common use cases:**

- **Clone a repository** — `git clone` a repo into the working directory so the
  assistant can work on it immediately.
- **Install dependencies** — run `npm install`, `pip install -r requirements.txt`, or
  `mvn dependency:resolve` to prepare the project.
- **Write configuration files** — generate `.env`, `settings.json`, or other config
  files that the assistant or its tools will need.
- **Fetch context** — download issue details, pull request metadata, or other context
  from external APIs and save it to a file the assistant can read.

**Example (bash):**

```bash
#!/bin/bash
# Clone a specific repo branch into the working directory
git clone --branch main --depth 1 \
  https://${GH_TOKEN}@github.com/my-org/my-repo.git .
npm install
```

**Example (Node.js):**

```javascript
const fs = require("fs");
const resp = await fetch("https://api.github.com/repos/my-org/my-repo/issues/42", {
    headers: { Authorization: `Bearer ${process.env.GH_TOKEN}` },
});
const issue = await resp.json();
fs.writeFileSync("context.json", JSON.stringify(issue, null, 2));
```

---

## Creating a Session

1. Click **New Session** on the Assistant page.
2. A **template picker** modal appears. Browse or search available templates — each
   shows its name and description.
3. Select a template.
4. A **name modal** appears with an auto-generated fun name (e.g.,
   "rocket-penguin-waffle"). You can accept or change it.
5. Click **Create** to start the session.

Axiom supports a limited number of concurrent sessions (default: 3). Idle sessions are
automatically destroyed after one hour.

---

## The Session Interface

### Header Toolbar

The session header toolbar provides quick access to session controls and status:

- **Session name** — displayed prominently, editable inline via the pencil icon
- **Plan Mode** — an orange label appears when the assistant is in plan mode
- **Model** — shows the AI model in use for this session
- **Running cost** — real-time cost display (e.g., `$0.0342`) that accumulates per turn
- **Auto-approval badge** — shield icon with a count of active auto-approval rules
- **Apply All** — imports generated items (Config Assistant sessions only; disabled when
  no items exist)
- **End Session** — stops the session with a confirmation prompt
- **Breakout Window** — opens the session in a dedicated browser tab for a focused,
  full-screen experience

### Chat Panel

The chat panel is a conversation interface where you interact with the assistant:

- **Your messages** appear right-aligned in blue bubbles
- **Assistant messages** appear left-aligned in grey, with full markdown rendering
  (tables, code blocks, lists) via react-markdown
- **Tool use blocks** are expandable and color-coded by category:
    - Green — axiom-sdk tools
    - Purple — axiom-tools
    - Orange — plan/agent operations
    - Teal — AskUser interactions

#### Slash Commands

Type `/` in the chat input to see available slash commands. Use arrow keys to navigate
the autocomplete list and Tab or Enter to select a command. The available commands are
populated from the Claude Code session.

#### Permission Prompts

When the assistant needs to use a tool, a permission prompt appears with **Allow** and
**Deny** buttons. You can also click **Allow Pattern...** to create an auto-approval
rule so similar requests are approved automatically in the future.

Special permission types have dedicated UX:

- **AskUserQuestion** — renders structured multi-choice options
- **ExitPlanMode** — displays the plan as rendered markdown with **Approve** and
  **Reject** buttons

### Plan Mode

When the assistant enters plan mode, the session header shows visual indicators: an
orange border and a "Plan Mode" label. The plan is rendered as markdown, and you can
**Approve** or **Reject** it before the assistant proceeds.

---

## Auto-Approval Rules

Auto-approval rules let you skip repetitive permission prompts for trusted tool
operations. Each rule defines:

- **Tool name** — the tool to auto-approve (e.g., `Read`, `Bash`)
- **Field name** (optional) — a specific input field to match
- **Regex pattern** (optional) — a regular expression the field value must match

For example, you could auto-approve all `Read` tool calls where the file path matches
`/src/.*` to avoid approving every source file read individually.

Rules are scoped to the current session and managed via the shield icon in the toolbar.
Click the icon to view active rules or delete individual ones.

---

## Breakout Window

Click the **Breakout Window** button in the session toolbar to open the session in a
dedicated browser tab. This hides the Axiom masthead and sidebar, giving you a focused,
full-screen chat experience. The breakout window stays connected to the same session —
you can switch between the main window and the breakout at any time.

---

## Cost Monitoring

The session toolbar displays a running cost for the current session (e.g.,
`$0.0342`). This accumulates in real time as the assistant processes each turn,
tracking input tokens, output tokens, and duration. Use this to monitor how much a
session is consuming, especially for long or complex conversations.

---

## Configuration Assistant

The **Configuration Assistant** template (`axiom-config-assistant`) provides a
specialized workflow for creating and updating Axiom configuration items. It extends
the standard session interface with a two-panel layout and an Apply workflow.

### Two-Panel Layout

- **Chat panel (70%)** — the standard conversation interface
- **Generated Items sidebar (30%)** — displays items the assistant has created

### Generated Items

As the assistant creates or updates items, they appear in the sidebar. The assistant
can work with five types of configuration items:

| Type | What it produces |
|------|-----------------|
| **Tools** | Script-based tools with parameters, descriptions, and bash script templates |
| **Action Types** | Actor or script-mode action types with prompt templates, allowed tools, and trigger settings |
| **Report Definitions** | Scheduled or ad-hoc reports with prompt templates, allowed tools, and time windows |
| **Toolsets** | Named groups of tools that can be referenced in action types and session templates using `@ToolsetName` |
| **Session Templates** | Custom AI Assistant session configurations with a persona, system prompt, MCP servers, and allowed tools |

Each item shows its type, name, and validation status — a green checkmark if valid, or
a warning/error icon if there are problems. Click any item to open a detail modal
showing its full configuration and any validation errors.

The assistant understands the relationships between these items. When it creates an
action type that needs a custom tool, it creates the tool first and adds it to the
action type's allowed tools list automatically.

### Validation

The assistant receives validation feedback in real time. When a generated item has
errors, those errors are sent back to Claude as messages, allowing the assistant to
fix issues automatically without you having to point them out.

### Applying Generated Items

Generated items exist only within the session until you apply them. Nothing is saved
to your Axiom configuration until you explicitly click **Apply All** in the session
header.

Apply All validates all items, then applies them to your Axiom configuration in one
atomic operation. Items with names matching existing configuration are **updated in
place**; items with new names are **created**. A summary dialog shows what was applied
with a breakdown of created and updated counts (e.g., "2 tool(s) created, 1 tool(s)
updated"). After applying, the session ends and you are returned to the sessions list.

!!! warning
    If you end a session without applying, all generated items are discarded. This
    cannot be undone.

### Querying Existing Configuration

The Configuration Assistant has access to an MCP server that lets it query your
existing Axiom configuration — it can list your current tools, action types, event
sources, and more to understand what is already set up. This is especially useful
when updating existing items, as the assistant fetches the current definition before
making changes.

### Example Conversation

Here is an example of how you might use the Configuration Assistant to create the
auto-labeling configuration from the
[Usage Examples](usage-examples.md#example-1-automatically-label-new-github-issues):

> **You:** I want to automatically label new GitHub issues. Create three tools: one to
> fetch the available labels from a repo, one to fetch an issue's details, and one to
> apply labels. Then create an action type that uses them.

The assistant will:

1. Create `fetch_github_labels`, `fetch_github_issue`, and `apply_github_labels` tools
   with appropriate parameters and scripts
2. Create an `Auto-Label Issue` action type with a prompt template referencing all three
   tools
3. Show all four items in the Generated Items panel with validation status

You can then review each item, ask for changes, and apply them all at once.

---

## Managing Sessions

The **Assistant Sessions** page lists all sessions with color-coded status labels:

- **Starting** (blue) — session is initializing
- **Running** (green) — session is active and ready
- **Stopped** (grey) — session has ended
- **Error** (red) — session encountered a problem

You can filter sessions by name or template, and delete sessions with a confirmation
modal.

---

## Tips

### Configuration Assistant Tips

- **Be specific about what you need.** "Create a tool that lists GitHub labels for a
  repository using the `gh` CLI" gives better results than "make a label tool."
- **Create related items together.** The assistant shines when creating a group of items
  that reference each other — tools plus the action type that uses them, for example.
- **Review before applying.** Click each generated item in the sidebar to inspect its
  full configuration and check for validation warnings.
- **Iterate in conversation.** If something isn't right, tell the assistant what to
  change rather than starting over. It can read and update the items it already created.
- **Update existing items by name.** Tell the assistant which item you want to modify
  and what to change. It will fetch the current definition, apply your changes, and
  write the updated JSON. When you apply, matching names are updated in place.
- **Ask about existing configuration.** The assistant can query your current tools,
  action types, toolsets, session templates, and other configuration to understand
  your setup before creating or updating items.

### General Tips

- **Use breakout windows for focused work.** When you need the full screen for a long
  conversation, open the session in a breakout window to hide the Axiom navigation.
- **Set up auto-approval rules early.** If you know the assistant will need to read
  files or run commands repeatedly, create auto-approval rules at the start of the
  session to avoid repeated permission prompts.
- **Use slash commands.** Type `/` to discover available Claude Code commands. These
  give you direct control over the assistant's behavior without typing full
  instructions.
- **Monitor costs.** Keep an eye on the running cost display in the toolbar, especially
  during complex multi-step tasks. End sessions when you are done to avoid unnecessary
  token usage.
- **Choose the right template.** Use the Configuration Assistant when you need to
  create or update Axiom configuration items. Use the General Assistant or a custom
  template for everything else.
