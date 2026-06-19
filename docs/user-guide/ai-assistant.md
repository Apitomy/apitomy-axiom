# AI Assistant

The AI Assistant is an interactive, conversational interface for creating Axiom
configuration items. Instead of manually filling out forms for each tool, action type,
and report definition, you describe what you want in plain language and the assistant
creates everything for you.

This is especially useful when you need to create multiple related items at once — for
example, a set of tools plus an action type that uses them, or a tool plus a report
definition that depends on it.

!!! note
    The AI Assistant is available only when the AI engine is set to **Claude Code**.
    It uses the Claude Code CLI directly, so the `claude` binary must be installed and
    available on your PATH.

---

## Accessing the Assistant

Click the robot icon in the top-right corner of the Axiom masthead.  The icon might
be pulsing if you have never clicked it before.

This takes you to the **Assistant Sessions** page, where you can create a new session
or resume an existing one.

---

## Creating a Session

Click **New Session** to start a conversation. Each session gets a randomly generated
name (e.g. "rocket-penguin-galaxy"), which you can change before creating it.

Axiom supports a limited number of concurrent sessions (default: 3). Idle sessions are
automatically destroyed after one hour.

---

## The Session Interface

A session has two panels:

### Chat Panel (left)

A conversation interface where you interact with the assistant. You can:

- **Describe what you want to create** — e.g. "Create a tool that fetches open PRs from
  a GitHub repository, then create a weekly report that summarizes them"
- **Refine generated items** — e.g. "Add a `repository` parameter to that tool" or
  "Change the schedule to daily"
- **Ask questions** — e.g. "What tools do I already have configured?" or "What
  placeholders can I use in a report prompt template?"
- **Approve tool usage** — the assistant may ask permission to run tools (like querying
  existing Axiom configuration). You can approve or deny each request.

The assistant has access to an MCP server that lets it query your existing Axiom
configuration — it can list your current tools, action types, event sources, and more
to understand what's already set up before creating new items.

### Generated Items Panel (right)

As the assistant creates items, they appear in the right panel. Each item shows:

- **Type** — Tool, Action Type, or Report Definition
- **Name** — the item's name
- **Validation status** — a green checkmark if valid, or a warning/error icon if there
  are problems

Click any item to open a detail modal showing its full configuration, including any
validation errors or warnings.

---

## What the Assistant Can Create

The assistant creates three types of configuration items:

| Type | What it produces |
|------|-----------------|
| **Tools** | Script-based tools with parameters, descriptions, and bash script templates |
| **Action Types** | Actor or script-mode action types with prompt templates, allowed tools, and trigger settings |
| **Report Definitions** | Scheduled or ad-hoc reports with prompt templates, allowed tools, and time windows |

The assistant understands the relationships between these items. When it creates an
action type that needs a custom tool, it creates the tool first and adds it to the
action type's allowed tools list automatically.

---

## Applying Generated Items

Generated items exist only within the session until you apply them. Nothing is saved to
your Axiom configuration until you explicitly choose to apply.

When you're satisfied with the generated items, click the **Apply All** button in the
session header. This imports all items into your Axiom configuration in one operation.
A summary dialog shows what was imported (e.g. "3 tools, 1 action type").

After applying, the session ends and you're returned to the sessions list. The imported
items are now live in your Axiom configuration and can be viewed and edited through the
normal configuration pages.

!!! warning
    If you end a session without applying, all generated items are discarded. This
    cannot be undone.

---

## Example Conversation

Here's an example of how you might use the assistant to create the auto-labeling
configuration from the [Usage Examples](usage-examples.md#example-1-automatically-label-new-github-issues):

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

## Tips

- **Be specific about what you need.** "Create a tool that lists GitHub labels for a
  repository using the `gh` CLI" gives better results than "make a label tool."
- **Create related items together.** The assistant shines when creating a group of items
  that reference each other — tools plus the action type that uses them, for example.
- **Review before applying.** Click each generated item in the right panel to inspect its
  full configuration and check for validation warnings.
- **Iterate in conversation.** If something isn't right, tell the assistant what to
  change rather than starting over. It can read and update the items it already created.
- **Ask about existing configuration.** The assistant can query your current tools,
  action types, and other configuration to avoid duplicates and understand your setup.
