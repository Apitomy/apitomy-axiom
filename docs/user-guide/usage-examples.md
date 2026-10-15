# Usage Examples

This section walks through two end-to-end examples showing how to configure Axiom for
real-world use cases. Each example explains the goal, lists every configuration item
needed, and provides the exact values to enter.

If you haven't read the [Concepts](concepts.md) guide yet, start there — it explains
the building blocks referenced below.

---

## Example 1: Automatically Label New GitHub Issues

### Goal

When a new issue is opened on a monitored GitHub repository, an AI agent reads the
issue title and body, examines the repository's available labels, chooses the most
relevant ones, and applies them — all automatically.

This example uses a **Subscription** with a `create-workflow` routing rule that starts a
**Workflow** whose single `action` node runs an **Action Type** in agent mode. The action type
allows three custom **Tools** that encapsulate the GitHub CLI commands. The AI agent can only call
these three tools, ensuring it can read issue details and apply labels but nothing else.

### What You'll Configure

| Item | Purpose |
|------|---------|
| Secret | GitHub API token for authentication |
| Event Source | Polls a GitHub repository for new activity |
| 3 Tools | Fetch labels, fetch issue details, apply labels |
| Action Type | Defines the auto-labeling behavior and prompt |
| Workflow | Runs the action type on the issue's subject |
| Subscription | Starts the workflow for each new issue |
| Agent | An AI agent slot to execute the task |

### Step 1: Create a Secret

Navigate to **Components > Secrets** and add a secret:

| Field | Value |
|-------|-------|
| **Name** | `GH_TOKEN` |
| **Description** | GitHub personal access token |
| **Value** | Your GitHub PAT with `repo` scope |

This token is encrypted at rest and injected as an environment variable into AI agent
subprocesses, where the `gh` CLI picks it up automatically.

### Step 2: Add an Event Source

Navigate to **Events > Event Sources** and click **Add Event Source**:

| Field | Value |
|-------|-------|
| **ID** | e.g. `github-com` |
| **Name** | e.g. `My Repository` |
| **Source Type** | GitHub |
| **Base URL** | `https://github.com` |
| **Repositories** | `your-org/your-repo` |
| **Enabled** | Yes |
| **Poll Interval** | `60` (seconds) |
| **Authentication Secret** | `GH_TOKEN` |

Axiom will now poll this repository every 60 seconds for new issues, pull requests,
comments, and other activity, and record it in the event stream.

### Step 3: Create Three Tools

Navigate to **Components > Tools** and create each of the following tools. For each
one, click **Create Tool**, enter the name, then configure the description, parameters,
and script template on the detail page.

#### Tool: `fetch_github_labels`

Lists all labels available on a repository. The AI agent uses this to know which labels
it can choose from.

**Info tab:**

| Field | Value |
|-------|-------|
| **Name** | `fetch_github_labels` |
| **Description** | List all labels available on a GitHub repository |

**Parameters tab** — add one parameter:

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `repository` | string | Yes | Repository in owner/repo format |

**Script Template tab:**

```bash
#!/bin/bash
set -euo pipefail
gh label list --repo "{{repository}}" --json name,description --limit 100
```

---

#### Tool: `fetch_github_issue`

Fetches the title, body, and current labels of a specific issue. The AI agent uses this
to understand what the issue is about.

**Info tab:**

| Field | Value |
|-------|-------|
| **Name** | `fetch_github_issue` |
| **Description** | Fetch the title, body, and current labels of a GitHub issue |

**Parameters tab** — add one parameter:

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `issue_url` | string | Yes | Full URL of the issue |

**Script Template tab:**

```bash
#!/bin/bash
set -euo pipefail
gh issue view "{{issue_url}}" --json title,body,labels
```

The `gh` CLI accepts a full issue URL anywhere it accepts an issue reference, so this
tool works directly with the `ref` value Axiom passes to the agent.

---

#### Tool: `apply_github_labels`

Adds labels to an issue. This is the only write operation the AI agent can perform.

**Info tab:**

| Field | Value |
|-------|-------|
| **Name** | `apply_github_labels` |
| **Description** | Add one or more labels to a GitHub issue |

**Parameters tab** — add two parameters:

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `issue_url` | string | Yes | Full URL of the issue |
| `labels` | string | Yes | Comma-separated list of label names to apply |

**Script Template tab:**

```bash
#!/bin/bash
set -euo pipefail
gh issue edit "{{issue_url}}" --add-label "{{labels}}"
```

### Step 4: Create the Action Type

Navigate to **Components > Action Types** and click **Create Action Type**:

| Field | Value |
|-------|-------|
| **Name** | `Auto-Label Issue` |
| **Execution Mode** | Agent — AI agent |

On the detail page, configure the remaining fields:

**Info tab:**

| Field | Value |
|-------|-------|
| **Description** | Analyze a new GitHub issue and apply relevant labels |

**Inputs tab** — declare what the action operates on:

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `ref` | string | Yes | URL of the issue to label |
| `repository` | string | Yes | Repository in owner/repo format |

**Allowed Tools tab** — add exactly these three tools:

```
fetch_github_labels
fetch_github_issue
apply_github_labels
```

**Prompt Template tab:**

```markdown
You are a GitHub issue labeling assistant. A new issue has been created and your job
is to apply the most relevant labels from the repository's existing label set.

## Instructions

1. Fetch the issue details using the `fetch_github_issue` tool with issue URL
   `{{inputs.ref}}`.

2. Fetch the available labels using the `fetch_github_labels` tool with repository
   `{{inputs.repository}}`.

3. Based on the issue title and body, select the most relevant labels from the
   available set. Choose labels that accurately categorize the issue. Typically
   1-3 labels is appropriate. Do not invent new labels — only use labels that
   exist in the repository.

4. Apply the selected labels using the `apply_github_labels` tool with issue URL
   `{{inputs.ref}}`.

If the issue already has appropriate labels, or if no labels clearly apply, do not
make any changes.
```

### Step 5: Create the Workflow

Navigate to **Components > Workflows** and click **Create Workflow Definition** with the name
`Auto-Label New Issues`. The new definition is scaffolded with a Start node (declaring `subjectId`,
`ref`, and `event`) connected to an End node. In the editor:

1. Drag an **Auto-Label Issue** action node from the palette (every action type appears there) between
   Start and End
2. Double-click the action node to open its properties and map its inputs from the run context:
   `ref` ← `context.ref` and `repository` ← `context.repository`
3. Connect Start → Auto-Label Issue → End
4. Click **Publish**

!!! tip "Using the Workbench"
    You can also build the workflow, the action type and the tools together in the
    [Workbench](workbench.md), with the AI Assistant's help, and publish them in one step.

### Step 6: Add a Subscription

Navigate to **Events > Subscriptions** and click **Add Subscription** with the name
`Label new issues`. Then configure it on the detail page:

| Field | Value |
|-------|-------|
| **Filter Expression** | `event.type == 'issue.created'` |
| **Enabled** | Yes |

Under **Routing Rules**, add one rule of type **Create Workflow** and select the
`Auto-Label New Issues` definition. Use the **Preview** on the subscription page to confirm the filter
matches the events you expect, then click **Save Changes**.

### Step 7: Ensure a Matching Agent Exists

Navigate to **Components > Agents**. You need at least one enabled agent whose
capabilities match this action type. A new installation already has four agents with capability `*`.
If none exist, click **Create Agent**:

| Field | Value |
|-------|-------|
| **Name** | e.g. `AI Agent` |
| **Type** | Claude Code (or your preferred agent type) |

Then add the capability `*` on the agent's **Capabilities** tab.

A capability of `*` matches any work, including the `action:Auto-Label Issue`
capability this task will request. See
[AI Agents](concepts.md#ai-agents) for how capability matching works.

### How It Works End to End

1. Someone opens a new issue on the monitored repository
2. The Event Source detects the new issue on its next poll and records it in the event
   stream
3. The Subscription's filter matches the `issue.created` event, and its `create-workflow` rule finds or
   creates a subject for the issue's URL and starts an `Auto-Label New Issues` run on it
4. The run reaches the action node, which creates a Task leased by an available agent whose
   capabilities match `action:Auto-Label Issue`
5. The agent reads the issue, checks available labels, picks the best matches, and
   applies them
6. The task completes, the run finishes, and the result is visible on the **Runs** page and on the
   subject's detail page

---

## Example 2: Weekly GitHub @Mentions Summary

### Goal

Every Monday, summarize all GitHub issues and pull requests where you were @mentioned in the past
7 days, organized by repository, and post the summary to a dedicated subject so the history of weekly
summaries accumulates in one place.

This example uses a **Workflow** with a **Schedule Trigger**. The workflow's action node runs an
**Action Type** whose agent calls a custom **Tool** that queries the GitHub notifications API, then posts
the summary to the subject's thread with the `axiom_add_thread_entry` SDK function.

### What You'll Configure

| Item | Purpose |
|------|---------|
| Secret | GitHub API token for authentication |
| Tool | Queries GitHub notifications API for @mentions |
| Action Type | Defines the summary prompt and allowed tools |
| Workflow + Schedule Trigger | Runs the action type every Monday on a fixed subject |
| Agent | An AI agent slot to execute the task |

### Step 1: Create a Secret

If you already created a `GH_TOKEN` secret (e.g. from Example 1), skip this step.

Navigate to **Components > Secrets** and add:

| Field | Value |
|-------|-------|
| **Name** | `GH_TOKEN` |
| **Description** | GitHub personal access token |
| **Value** | Your GitHub PAT with `notifications` scope |

### Step 2: Create the Tool

Navigate to **Components > Tools** and click **Create Tool** with the name
`github_mentions`. Then configure it on the detail page:

#### Tool: `github_mentions`

Queries the GitHub notifications API for all @mention notifications since a given date.

**Info tab:**

| Field | Value |
|-------|-------|
| **Name** | `github_mentions` |
| **Description** | Fetch all GitHub @mention notifications since a given date |

**Parameters tab** — add one parameter:

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `since` | string | Yes | ISO date (e.g. 2026-01-01T00:00:00Z) |

**Script Template tab:**

```bash
#!/bin/bash
set -euo pipefail
gh api /notifications \
  -X GET \
  -f all=true \
  -f since="{{since}}" \
  --jq '[.[] | select(.reason == "mention") | {
    repo: .repository.full_name,
    title: .subject.title,
    type: .subject.type,
    url: .subject.url,
    updated: .updated_at
  }]'
```

This script uses the GitHub notifications API with `reason == "mention"` to filter for
only @mentions. The `since` parameter limits results to the summary's time window.

### Step 3: Create the Action Type

Navigate to **Components > Action Types** and click **Create Action Type** with the name
`Summarize @Mentions` and execution mode **Agent — AI agent**. On the detail page:

**Allowed Tools tab** — add the tool and the SDK functions used to find the subject and post to its
thread:

```
github_mentions
mcp__axiom-sdk__axiom_list_subjects
mcp__axiom-sdk__axiom_add_thread_entry
```

**Inputs tab** — add a required `subjectName` input (string, "Name of the subject to post the summary
to").

**Prompt Template tab:**

```markdown
Summarize all GitHub @mentions from the past 7 days.

1. Compute the date 7 days ago (ISO 8601, UTC) and run the `github_mentions` tool with
   that value as `since`.
2. Produce a Markdown summary with:
   - Total number of mentions, repositories involved, and a breakdown by type (Issue vs PR)
   - For each repository (alphabetically), each mention with its title, type, and date
     (newest first) and a one-line note on whether it likely needs a response
   - An "Action Items" section listing mentions that need follow-up (or "None")
3. Find the ID of the subject named "{{inputs.subjectName}}" with `axiom_list_subjects`, then post
   the summary to that subject's thread with `axiom_add_thread_entry`.
```

### Step 4: Create the Workflow and Schedule Trigger

Navigate to **Components > Workflows**, create a workflow definition named `Weekly @Mentions`, add a
**Summarize @Mentions** action node between Start and End, map its `subjectName` input from
`context.subjectName`, and click **Publish**.

On the workflow's **Triggers** tab, add a schedule trigger:

| Field | Value |
|-------|-------|
| **Cron** | `0 8 * * 1` (Mondays at 08:00 UTC) |
| **Subject name** | `Weekly @Mentions` |
| **Enabled** | Yes |

### Step 5: Ensure a Matching Agent Exists

Navigate to **Components > Agents** and confirm at least one enabled agent's
capabilities match `action:Summarize @Mentions`. A capability of `*` or `action:*` matches any action
type.

### How It Works End to End

1. Every Monday at 08:00 UTC, the schedule trigger fires
2. On the first firing, Axiom creates the `Weekly @Mentions` subject (ref `schedule:Weekly @Mentions`);
   later firings reuse it
3. A `Weekly @Mentions` run starts on that subject — unless the previous week's run is somehow still
   active, in which case the firing is skipped and the trigger's last outcome shows `skipped`
4. The action node creates a task; an agent matching `action:Summarize @Mentions` is leased from the pool
5. The agent calls `github_mentions`, organizes the results, and posts the summary to the subject's
   thread
6. Each week's summary is visible on the subject's detail page, alongside the timeline of past runs
