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

This example uses an **Action Type** in actor mode with three custom **Tools** that
encapsulate the GitHub CLI commands. The AI agent can only call these three tools,
ensuring it can read issue details and apply labels but nothing else.

### What You'll Configure

| Item | Purpose |
|------|---------|
| Secret | GitHub API token for authentication |
| Event Source | Polls a GitHub repository for new issues |
| 3 Tools | Fetch labels, fetch issue details, apply labels |
| Action Type | Defines the auto-labeling behavior and prompt |
| Actor | An AI agent to execute the task |

### Step 1: Create a Secret

Navigate to **Configuration > Secrets** and add a secret:

| Field | Value |
|-------|-------|
| **Name** | `GH_TOKEN` |
| **Description** | GitHub personal access token |
| **Value** | Your GitHub PAT with `repo` scope |

This token is encrypted at rest and injected as an environment variable into AI agent
subprocesses, where the `gh` CLI picks it up automatically.

### Step 2: Add an Event Source

Navigate to **Configuration > Event Sources** and click **Add Event Source**:

| Field | Value |
|-------|-------|
| **Name** | e.g. `My Project` |
| **Source Type** | GitHub |
| **Repository URL** | `https://github.com/your-org/your-repo` |
| **Enabled** | Yes |
| **Poll Interval** | `60` (seconds) |
| **Authentication Secret** | `GH_TOKEN` |

Axiom will now poll this repository every 60 seconds for new issues, pull requests,
comments, and other activity.

### Step 3: Create Three Tools

Navigate to **Configuration > Tools** and create each of the following tools. For each
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
| `issue_ref` | string | Yes | Issue reference (e.g. owner/repo#42) |

**Script Template tab:**

```bash
#!/bin/bash
set -euo pipefail
gh issue view "{{issue_ref}}" --json title,body,labels
```

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
| `issue_ref` | string | Yes | Issue reference (e.g. owner/repo#42) |
| `labels` | string | Yes | Comma-separated list of label names to apply |

**Script Template tab:**

```bash
#!/bin/bash
set -euo pipefail
gh issue edit "{{issue_ref}}" --add-label "{{labels}}"
```

### Step 4: Create the Action Type

Navigate to **Configuration > Action Types** and click **Create Action Type**:

| Field | Value |
|-------|-------|
| **Name** | `Auto-Label Issue` |
| **Execution Mode** | Actor |

On the detail page, configure the remaining fields:

**Info tab:**

| Field | Value |
|-------|-------|
| **Description** | Analyze a new GitHub issue and apply relevant labels |
| **Manager triggerable** | Yes |
| **User triggerable** | No |
| **Emits event** | No |

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

1. Fetch the issue details using the `fetch_github_issue` tool with issue reference
   `{{issueRef}}`.

2. Fetch the available labels using the `fetch_github_labels` tool with repository
   `{{repository}}`.

3. Based on the issue title and body, select the most relevant labels from the
   available set. Choose labels that accurately categorize the issue. Typically
   1-3 labels is appropriate. Do not invent new labels — only use labels that
   exist in the repository.

4. Apply the selected labels using the `apply_github_labels` tool.

If the issue already has appropriate labels, or if no labels clearly apply, do not
make any changes.
```

### Step 5: Ensure an Actor Exists

Navigate to **Configuration > Actors**. You need at least one AI agent actor. If none
exist, click **Create Actor**:

| Field | Value           |
|-------|-----------------|
| **Name** | e.g. `AI Agent` |
| **Type** | AI Agent        |
| **Capabilities** | `*`             |

### Step 6: Verify the Manager Configuration

No changes are needed to the Manager for this example. The Manager's default prompt
template includes the `{{actionTypes}}` placeholder, which automatically lists all
configured action types — including the new `Auto-Label Issue` type. When the Manager
sees an `issue-created` event, it will recognize this action type as an option and can
assign it to an available actor.

You can review the Manager's prompt template under **Configuration > Manager** to
confirm the `{{actionTypes}}` placeholder is present.

### How It Works End to End

1. Someone opens a new issue on the monitored repository
2. The Event Source detects the new issue on its next poll
3. The AI Manager triages the event and decides to assign the `Auto-Label Issue`
   action type
4. A Task is created and assigned to an available AI agent actor
5. The actor reads the issue, checks available labels, picks the best matches, and
   applies them
6. The task completes and the result is visible on the project's detail page

---

## Example 2: Weekly GitHub @Mentions Report

### Goal

Generate a weekly report summarizing all GitHub issues and pull requests where you were
@mentioned in the past 7 days. The report is organized by repository and highlights
items that may need your attention.

This example uses a **Report Definition** with a custom **Tool** that queries the
GitHub notifications API for @mentions.

### What You'll Configure

| Item | Purpose |
|------|---------|
| Secret | GitHub API token for authentication |
| Tool | Queries GitHub notifications API for @mentions |
| Report Definition | Defines the schedule, time window, and prompt |

### Step 1: Create a Secret

If you already created a `GH_TOKEN` secret (e.g. from Example 1), skip this step.

Navigate to **Configuration > Secrets** and add:

| Field | Value |
|-------|-------|
| **Name** | `GH_TOKEN` |
| **Description** | GitHub personal access token |
| **Value** | Your GitHub PAT with `notifications` scope |

### Step 2: Create the Tool

Navigate to **Configuration > Tools** and click **Create Tool** with the name
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
only @mentions. The `since` parameter limits results to the report's time window.

### Step 3: Create the Report Definition

Navigate to **Configuration > Report Definitions** and click **Create Definition**:

| Field | Value |
|-------|-------|
| **Name** | `Weekly @Mentions` |
| **Schedule** | Weekly |

On the detail page, configure the remaining fields:

**Info tab:**

| Field | Value |
|-------|-------|
| **Description** | Weekly summary of GitHub @mentions across all repositories |
| **Schedule** | Weekly |
| **Day of Week** | Monday |
| **Time of Day** | `08:00` |
| **Time Window** | Last 7 days |
| **Enabled** | Yes |

**Allowed Tools tab** — add:

```
github_mentions
```

**Prompt Template tab:**

```markdown
Generate a report summarizing all GitHub @mentions from the past week.

## Data Collection

Run the `github_mentions` tool with the `since` parameter set to `{{timeRangeStart}}`
to fetch all @mention notifications.

## Report Structure

Produce a Markdown report with the following structure:

### Header
- Title: "Weekly @Mentions Report"
- Date range: {{timeRangeStart}} to {{timeRangeEnd}}

### Summary
- Total number of mentions
- Number of repositories involved
- Breakdown by type (Issue vs Pull Request)

### Mentions by Repository
For each repository that has mentions:
- List each mention with its title, type (Issue/PR), and date
- Include a one-line note on whether the item likely needs a response or action
  (e.g. a question directed at you vs. an FYI tag)

### Action Items
Highlight any mentions that appear to need a response or follow-up, based on the
title and context. If none need attention, note that explicitly.

## Formatting
- Use clean, concise Markdown
- Use bullet points, not paragraphs
- Sort repositories alphabetically
- Sort mentions within each repository by date (newest first)
```

### How It Works End to End

1. Every Monday at 08:00, Axiom triggers the `Weekly @Mentions` report definition
2. An AI agent is launched with the prompt template (placeholders substituted with
   the actual date range)
3. The agent calls the `github_mentions` tool with the start date of the time window
4. The tool queries the GitHub notifications API and returns all @mentions from the
   past 7 days
5. The agent organizes the results into a structured Markdown report
6. The report is saved and viewable under **Reports** in the sidebar

You can also click **Run Now** on the report definition page to generate a report
immediately without waiting for the schedule.
