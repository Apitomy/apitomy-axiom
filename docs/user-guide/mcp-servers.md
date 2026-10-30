# MCP Servers

MCP (Model Context Protocol) servers give AI agents extra tools: GitHub, Jira, databases, browsers, and many
more. Axiom keeps a list of the MCP servers you have configured. Action types and AI Assistant session
templates choose which of them an agent gets.

This page covers adding servers (from the catalog, from a JSON snippet, or by hand), credentials, testing,
using servers in workflows and sessions, and troubleshooting. Open **Components → MCP Servers** in the UI to
follow along.

## Concepts

- **Name.** Every server has a unique name, used in tool names: the `list_issues` tool of a server named
  `github` is `mcp__github__list_issues`. Names use letters, digits, `-` and `_`, start and end with a
  letter or digit, are at most 64 characters, and must not contain `__`.
- **Transport.** How Axiom talks to the server:
    - **stdio**: Axiom starts the server as a local process (`command` plus `arguments`) on the Axiom host.
      Most servers run on demand with `npx` (Node.js), `uvx` (Python) or `docker`, which download the package
      the first time.
    - **HTTP**: a remote server using streamable HTTP, at a URL.
    - **SSE**: a remote server using the older HTTP + server-sent events transport.
- **Environment** (stdio) and **headers** (HTTP/SSE). Values the server needs, typically credentials. Each
  value is either plain text or a reference to an Axiom secret (see [Credentials](#credentials-and-secrets)).
- **Timeout.** Optional, in milliseconds. Used by OpenCode and by connection tests; Claude Code uses its own
  default.
- **Enabled.** A disabled server is kept, but never loaded in sessions or tasks.

## Adding a server

### From the catalog

Click **Add from catalog**.

- **Popular** lists servers that ship with Axiom, including GitHub, Jira & Confluence, Slack, Notion,
  PostgreSQL, Filesystem, Git, Fetch, Brave Search, Context7 and Playwright.
- **MCP Registry** searches the [official MCP Registry](https://registry.modelcontextprotocol.io). Its servers
  are published by their authors and are not reviewed by Axiom: check a server before giving it credentials.
  This tab needs internet access from the Axiom host.

Each entry shows what it needs to run: **Node.js** (`npx`), **uv** (`uvx`), **Docker**, or **Remote**. Axiom
warns when the runner is not installed on the Axiom host.

Select an entry to set it up. Axiom asks for the server name and for each value the server needs. For
credentials you can:

- enter a **new secret**: Axiom suggests a secret name and saves the value as an Axiom secret when you add the
  server;
- pick an **existing secret**; or
- enter a **plain value** (stored as plain text; not recommended for credentials).

Header values are filled into the server's template, so a GitHub token becomes
`Authorization: Bearer ${secret:GITHUB_TOKEN}`. Click **Test** to try the server with the values you entered
(no secrets are created), then **Add server**.

!!! example "GitHub and Jira"

    - **GitHub** uses GitHub's official remote server. Create a fine-grained personal access token at
      <https://github.com/settings/personal-access-tokens> with access to the repositories the agent should
      use, and enter it as a new secret.
    - **Jira & Confluence** runs `uvx mcp-atlassian` and needs `uv` on the Axiom host. Enter your Jira URL and
      account email, and an API token from <https://id.atlassian.com/manage-profile/security/api-tokens> as a
      new secret. Leave the Confluence values empty to use Jira only.

### From a JSON snippet

Most MCP servers document their setup as a JSON snippet. Click **Add MCP Server**, open **Paste JSON** and
paste:

- an `mcpServers` block, as used by Claude Code, Cursor, Windsurf and Cline;
- an OpenCode `mcp` block; or
- a map of server names to servers.

```json
{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "${secret:GITHUB_TOKEN}" }
    }
  }
}
```

Axiom shows the servers it found and warns about plain values whose names look like credentials, such as
`API_KEY` or `TOKEN`. You can import several servers at once.

### By hand

Click **Add MCP Server**, enter a name, choose the transport and enter the command (and arguments) or URL.
**Test** checks the server before you create it. Environment variables, headers and the timeout are set on
the server's page afterwards.

## Credentials and secrets

Environment and header values can be:

- **Plain text**: stored as entered, visible to anyone who can see the server, and written to configuration
  pack exports.
- **Secret references**: `${secret:NAME}`, optionally with surrounding text such as `Bearer ${secret:NAME}`.
  The server stores only the reference. Axiom resolves it when a session or task starts, writes the value
  only into that run's MCP configuration file (readable only by the Axiom user), and never logs it.

Use secret references for credentials. The server's page warns when it refers to a secret that does not
exist. If a referenced secret is missing when a task or session starts, the task fails, or the session does
not start, with a message naming the server and the secret.

An MCP server gets **only** its own environment. It does not receive the variables of the task or session
that uses it (not even the action type's declared secrets), so declare everything the server needs on the
server itself.

## Testing a server and listing its tools

On a server's page, **Test connection** starts or connects to the server, performs the MCP handshake and
lists its tools. It never calls a tool. The result shows the server's name and version and each tool under
its allowed-tool name (`mcp__<server>__<tool>`). When a test fails, you see the error and the server's output
(for stdio servers, what it wrote to stderr), which usually explains the problem, such as a missing token or
a package that could not be downloaded.

A successful test saves the tool list. It appears on the **Tools** tab, and the allowed-tools editor of
action types and session templates suggests those tool names. Test again after a server is upgraded.

Tests resolve secret references and work for disabled servers. They wait for the server's timeout, or 60
seconds when none is set (up to 120 seconds).

## Using servers

### In workflows (action types)

Agent action types have an **MCP Servers** tab. Each selected server is started for tasks of that type, and
all of its tools are allowed (`mcp__<server>__*`).

To allow only some tools of a server, do not select it. Instead, add the individual tools to **Allowed
Tools**, for example `mcp__github__list_issues`. A server is also started when any of its tools is listed
there, directly or through a toolset.

MCP servers are never loaded implicitly: an action type gets only the servers it selects or whose tools it
allows.

### In AI Assistant sessions

Session templates have an **MCP Servers** list on the **Tools & Servers** tab. It contains Axiom's built-in
servers (`@axiom-assistant`, `@axiom-workbench`, `@axiom-tools`, `@axiom-sdk`) and your configured ones. As
with action types, servers whose tools appear in **Allowed Tools** are added automatically. If a template
selects a server that no longer exists, sessions from it do not start until you fix the template.

### Engines

The same configuration is used with every engine:

- **Claude Code and GitHub Copilot** get an MCP configuration file per task or session.
- **OpenCode** sessions get the servers in their `opencode.json`. For OpenCode tasks, Axiom registers the
  servers with the shared OpenCode server, re-registers them when you edit them, and disconnects them when
  they are deleted or disabled.

## Renaming, disabling and deleting

- **Renaming** a server updates every action type, session template and toolset that uses it, including
  `mcp__<old>__…` tool names.
- **Disabling** keeps the server and its references but stops loading it.
- **Deleting** is refused while anything uses the server. The **Used by** tab (and the error when deleting)
  lists the action types, session templates and toolsets that reference it.

## Configuration packs

MCP servers can be included in configuration packs. Secret references are exported as references. Plain
values are exported as they are, so the export page warns about plain values that look like credentials.
On import, servers are validated like servers created in the UI. Action types and session templates in the
pack may only refer to servers that exist or are in the pack. When a server with the same name already
exists, choose whether to cancel the import, skip existing items or overwrite them.

## Troubleshooting

| Problem | What to check |
|---------|---------------|
| `Could not start 'npx'` (or `uvx`, `docker`) | The runner is not installed or not on the Axiom process's `PATH`. The catalog shows which runners Axiom can find. |
| Test times out | The first run downloads the package; set a larger **Timeout** or run the test again. Remote servers: check the URL and network access from the Axiom host. |
| `HTTP 401` / `403` | The header value (usually `Authorization: Bearer …`) is missing or wrong, or the token lacks permissions. |
| `Server exited with code …` | Open **Server output** in the test result; servers usually print what is missing, such as an environment variable. |
| `Secret 'X' does not exist` | Create the secret under **Components → Secrets**, or change the reference. |
| Tools are not available to a task | Select the server on the action type, or add its tools to **Allowed Tools**; check that the server is enabled. |
