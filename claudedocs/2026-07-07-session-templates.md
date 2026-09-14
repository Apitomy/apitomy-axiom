# AI Assistant Session Templates — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax
> for tracking.

**Goal:** Introduce configurable session templates so the AI Assistant supports arbitrary tasks beyond
Axiom configuration, while preserving the existing Config Assistant experience as a built-in template.

**Architecture:** Session templates are a new first-class entity with two sources: built-in JSON files
on the classpath (immutable) and user-defined records in the database (full CRUD). The existing
hardcoded Config Assistant behavior is extracted into the first built-in template. Sessions are created
from templates, and the template's configuration drives the Claude Code subprocess setup (system prompt,
MCP servers, allowed tools, working directory). The Config Assistant sidebar, Apply button, and
validation features are feature-flagged by template ID.

**Tech Stack:** Java 25 / Quarkus 3.33 (Panache ORM, Flyway, JAX-RS, virtual threads), React 19 /
TypeScript / PatternFly 6, OpenAPI-first code generation.

## Global Constraints

- All entities use Quarkus Panache (`extends PanacheEntity`) with public fields (no getters/setters)
- All REST resources use `@RunOnVirtualThread`
- API-first: OpenAPI spec changes must come first; Java API beans are generated from the spec
- Flyway migrations use `CREATE TABLE IF NOT EXISTS`, `BIGINT AUTO_INCREMENT PRIMARY KEY`, sequences
  with `START WITH 1 INCREMENT BY 50`
- Frontend uses PatternFly 6 components, raw `fetch()` for API calls, React Router v6 flat routes
- Java imports use `org.jboss.logging.Logger` (not `java.util.logging`)
- Collection tables: no `id` column, `UNIQUE` constraint on pair, `ON DELETE CASCADE`, index on
  lookup columns
- Built-in template ID for the Config Assistant: `axiom-config-assistant`
- User instructions: no `var` where ambiguous, 4-space indentation, camelCase variables, PascalCase
  classes, Javadoc on public methods
- User instructions: do not run tests or build with maven — the user handles compilation and testing

---

### Task 1: Database Migration and Entity

Create the `session_template` database table, its collection tables, and the corresponding Panache
entity.

**Files:**
- Create: `app/src/main/resources/db/migration/V24__create_session_template.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/SessionTemplateEntity.java`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: `SessionTemplateEntity` with fields `templateId` (String, unique), `name` (String),
  `description` (String), `systemPrompt` (String), `welcomeMessage` (String), `workingDirectory`
  (String), `mcpServers` (List\<String\>), `toolsets` (List\<String\>), `allowedTools` (List\<String\>).
  Panache finder: `SessionTemplateEntity.find("templateId", id)`.

- [ ] **Step 1: Create Flyway migration**

Create `app/src/main/resources/db/migration/V24__create_session_template.sql`:

```sql
-- ============================================================
-- V24: Create session template tables for AI Assistant
-- ============================================================

CREATE TABLE IF NOT EXISTS session_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    system_prompt TEXT NOT NULL,
    welcome_message TEXT,
    working_directory VARCHAR(1024)
);

CREATE SEQUENCE IF NOT EXISTS session_template_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS session_template_mcp_server (
    session_template_id BIGINT NOT NULL,
    mcp_server_name VARCHAR(255) NOT NULL,
    UNIQUE (session_template_id, mcp_server_name),
    FOREIGN KEY (session_template_id) REFERENCES session_template(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS session_template_toolset (
    session_template_id BIGINT NOT NULL,
    toolset_name VARCHAR(255) NOT NULL,
    UNIQUE (session_template_id, toolset_name),
    FOREIGN KEY (session_template_id) REFERENCES session_template(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS session_template_allowed_tool (
    session_template_id BIGINT NOT NULL,
    tool_pattern VARCHAR(255) NOT NULL,
    UNIQUE (session_template_id, tool_pattern),
    FOREIGN KEY (session_template_id) REFERENCES session_template(id) ON DELETE CASCADE
);
```

- [ ] **Step 2: Create SessionTemplateEntity**

Create `core/src/main/java/io/apitomy/axiom/core/entities/SessionTemplateEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores a user-defined AI Assistant session template. Built-in templates
 * are loaded from classpath resources and are not stored in this table.
 */
@Entity
@Table(name = "session_template")
public class SessionTemplateEntity extends PanacheEntity {

    /** Unique template identifier (slug, e.g. "my-code-review-template"). */
    @Column(name = "template_id", nullable = false, unique = true)
    public String templateId;

    /** Display name shown in the template picker. */
    @Column(nullable = false)
    public String name;

    /** Brief description of what this template is for. */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String description;

    /** Markdown content written to CLAUDE.md in the session working directory. */
    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    public String systemPrompt;

    /** First message shown in the chat UI, attributed to the assistant. */
    @Column(name = "welcome_message", columnDefinition = "TEXT")
    public String welcomeMessage;

    /** Optional absolute path to an existing directory for the session. */
    @Column(name = "working_directory", length = 1024)
    public String workingDirectory;

    /** Names of McpServerEntity records to include in the session's MCP config. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_template_mcp_server",
            joinColumns = @JoinColumn(name = "session_template_id"))
    @Column(name = "mcp_server_name")
    public List<String> mcpServers = new ArrayList<>();

    /** Names of ToolsetEntity records whose tools are auto-approved. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_template_toolset",
            joinColumns = @JoinColumn(name = "session_template_id"))
    @Column(name = "toolset_name")
    public List<String> toolsets = new ArrayList<>();

    /** Additional explicit tool patterns for --allowedTools. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_template_allowed_tool",
            joinColumns = @JoinColumn(name = "session_template_id"))
    @Column(name = "tool_pattern")
    public List<String> allowedTools = new ArrayList<>();
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/db/migration/V24__create_session_template.sql \
       core/src/main/java/io/apitomy/axiom/core/entities/SessionTemplateEntity.java
git commit -m "Add session_template database table and Panache entity"
```

---

### Task 2: Built-in Template File and SessionTemplateService

Extract the existing Config Assistant hardcoded configuration into a built-in template JSON file, and
create the service that loads built-in templates from the classpath and merges them with database
templates.

**Files:**
- Create: `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json`
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java`

**Interfaces:**
- Consumes: `SessionTemplateEntity` (from Task 1), `McpServerEntity`, `ToolsetEntity`
- Produces: `SessionTemplateService` with methods:
  - `listTemplates(): List<SessionTemplate>` — merges built-in + database templates
  - `getTemplate(String templateId): SessionTemplate` — returns null if not found
  - `createTemplate(SessionTemplate template): SessionTemplate` — persists to DB
  - `updateTemplate(String templateId, SessionTemplate template): SessionTemplate` — rejects built-ins
  - `deleteTemplate(String templateId): void` — rejects built-ins
  - `isBuiltIn(String templateId): boolean`
  - Inner record `SessionTemplate(String templateId, String name, String description,
    String systemPrompt, String welcomeMessage, String workingDirectory, List<String> mcpServers,
    List<String> toolsets, List<String> allowedTools, boolean builtIn)`

- [ ] **Step 1: Create the built-in template JSON file**

Create `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json`.

The `systemPrompt` field should contain the exact markdown content currently returned by
`AssistantContextBuilder.buildClaudeMd()` (the full "Axiom Configuration Assistant" system prompt
from lines 80-206 of `AssistantContextBuilder.java`). The `welcomeMessage` should contain the
exact text currently hardcoded in `AssistantChatPanel.tsx` (lines 21-26).

```json
{
  "templateId": "axiom-config-assistant",
  "name": "Configuration Assistant",
  "description": "Create and refine Axiom configuration items — Tools, Action Types, and Report Definitions — through an interactive AI conversation.",
  "systemPrompt": "# Axiom Configuration Assistant\n\nYou are the **Axiom Configuration Assistant**. Your job is to help the user\ncreate and refine Axiom configuration items — **Tools**, **Action Types**, and\n**Report Definitions** — by writing well-formed JSON files in this working\ndirectory.\n\n## What You Can Create\n\n### Tools\nScript-based tools that AI agents can invoke. Each tool runs a bash script with\nparameter substitution.\n\nWrite each tool as a JSON file in the `tools/` subdirectory.\n\n**Schema:**\n```json\n{\n  \"name\": \"tool-name\",\n  \"description\": \"What the tool does\",\n  \"parameters\": [\n    {\n      \"name\": \"paramName\",\n      \"type\": \"string\",\n      \"description\": \"Parameter description\",\n      \"required\": true\n    }\n  ],\n  \"scriptTemplate\": \"#!/bin/bash\\n# Use {{paramName}} for parameter substitution\\necho {{paramName}}\",\n  \"labels\": [\"optional\", \"labels\"]\n}\n```\n\n**Template placeholders:** Use `{{paramName}}` in the script template. For\nparameters that contain multi-line content, use `{{paramName_file}}` — a temp\nfile path containing the value will be substituted instead.\n\n### Action Types\nDefine kinds of work that AI agents or scripts can perform.\n\nWrite each action type as a JSON file in the `action-types/` subdirectory.\n\n**Schema:**\n```json\n{\n  \"name\": \"action-type-name\",\n  \"description\": \"What this action does\",\n  \"executionMode\": \"actor\",\n  \"userTriggerable\": true,\n  \"managerTriggerable\": true,\n  \"emitsEvent\": false,\n  \"allowedTools\": [\"@Read-Only Tools\", \"mcp__axiom-tools__my-tool\", \"mcp__axiom-sdk__axiom_create_task\"],\n  \"promptTemplate\": \"You are performing...\\n\\nContext: {{input}}\",\n  \"scriptTemplate\": null,\n  \"model\": null,\n  \"engine\": null,\n  \"inputSchema\": null,\n  \"environment\": null\n}\n```\n\n**Execution modes:** `actor` (AI agent), `script` (bash script).\n\n**Allowed tools pattern:** Comma-separated list. Axiom has two built-in MCP\nservers with different prefixes:\n- `mcp__axiom-tools__<name>` — user-defined **script tools** (e.g.\n  `mcp__axiom-tools__post_github_comment`)\n- `mcp__axiom-sdk__axiom_<name>` — built-in **Axiom SDK tools** for\n  project/task management (e.g. `mcp__axiom-sdk__axiom_create_task`)\n- `@ToolsetName` — reference a named toolset (e.g. `@Read-Only Tools`,\n  `@Axiom SDK`)\n\n**Prompt template placeholders:** `{{input}}`, `{{projectId}}`,\n`{{projectName}}`, `{{repository}}`, `{{issueRef}}`.\n\n### Report Definitions\nRecurring or on-demand reports generated by AI agents.\n\nWrite each report definition as a JSON file in the `report-definitions/`\nsubdirectory.\n\n**Schema:**\n```json\n{\n  \"name\": \"report-name\",\n  \"description\": \"What this report covers\",\n  \"schedule\": \"weekly\",\n  \"scheduleTime\": \"08:00\",\n  \"scheduleDayOfWeek\": \"monday\",\n  \"timeWindow\": \"last-7d\",\n  \"promptTemplate\": \"Generate a report...\\n\\nRepositories: {{repositories}}\\nTime range: {{timeRangeStart}} to {{timeRangeEnd}}\",\n  \"allowedTools\": [\"mcp__axiom-tools__my-tool\"]\n}\n```\n\n**Schedule values:** `none`, `daily`, `weekly`, `monthly`, or a cron expression.\n\n**Time window values:** `since-last-run`, `last-24h`, `last-7d`, `last-30d`.\n\n**Prompt template placeholders:** `{{repositories}}`, `{{timeRangeStart}}`,\n`{{timeRangeEnd}}`, `{{timeWindow}}`.\n\n**Optional fields** (omit to use defaults):\n- `environment` — JSON object of environment variables. Omit if not needed.\n- `timeoutSeconds` — per-report timeout override. **Do not include this field**\n  unless the user explicitly requests a custom timeout. The system default\n  (600 seconds) is used when this field is absent.\n\n## Guidelines\n\n- **One file per item.** Name files descriptively (e.g. `tools/fetch-prs.json`).\n- **Use the MCP tools** (`axiom_list_tools`, `axiom_get_tool`, etc.) to discover\n  existing configuration before creating new items.\n- **Naming conventions:** Use lowercase kebab-case for names (e.g. `fetch-prs`,\n  `weekly-status`).\n- **Secret references:** Use `${secret:SECRET_NAME}` in script templates and\n  environment variables to reference secrets stored in Axiom.\n- **Validate your output.** Make sure JSON is well-formed and all required fields\n  are present.\n- **Allowed tools must match the prompt.** If a prompt template or script template\n  references specific tools (e.g. \"use the fetch-github-notifications tool\"),\n  those tools **must** be listed in `allowedTools`. An empty `allowedTools` means\n  the agent gets no tools at all. Always include every tool the agent will need.\n- When the user asks to modify an item, read the existing file, update it, and\n  write it back.",
  "welcomeMessage": "Hi! I'm the **Axiom Configuration Assistant**. I can help you create and refine:\n\n- **Tools** — script-based tools that AI agents can invoke\n- **Action Types** — define kinds of work for AI agents or scripts\n- **Report Definitions** — recurring or on-demand reports\n\nI can look up your existing configuration to understand what's already set up. Just describe what you'd like to create or ask me a question to get started!",
  "workingDirectory": null,
  "mcpServers": ["@axiom-assistant"],
  "toolsets": [],
  "allowedTools": [
    "Read(*)", "Write(*)", "Edit(*)",
    "Bash(ls *)", "Bash(cat *)",
    "mcp__axiom__axiom_list_tools",
    "mcp__axiom__axiom_get_tool",
    "mcp__axiom__axiom_list_action_types",
    "mcp__axiom__axiom_get_action_type",
    "mcp__axiom__axiom_list_report_definitions",
    "mcp__axiom__axiom_get_report_definition",
    "mcp__axiom__axiom_list_mcp_servers",
    "mcp__axiom__axiom_list_toolsets"
  ]
}
```

Note: The `"@axiom-assistant"` marker in `mcpServers` is a sentinel that the `AssistantSessionManager`
recognizes and handles specially (installing the built-in MCP server from classpath resources). Regular
MCP server names are resolved from `McpServerEntity` records.

- [ ] **Step 2: Create SessionTemplateService**

Create `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java`:

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.SessionTemplateEntity;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages AI Assistant session templates from two sources: immutable built-in
 * templates loaded from classpath resources, and user-defined templates stored
 * in the database.
 */
@ApplicationScoped
public class SessionTemplateService {

    private static final Logger LOG = Logger.getLogger(SessionTemplateService.class);
    private static final String TEMPLATES_RESOURCE_DIR = "templates/assistant-templates/";
    private static final String[] BUILT_IN_FILES = { "axiom-config-assistant.json" };

    @Inject
    ObjectMapper objectMapper;

    private final Map<String, SessionTemplate> builtInTemplates = new LinkedHashMap<>();

    /**
     * A session template definition (immutable value object).
     *
     * @param templateId unique identifier (slug for built-ins, UUID for user-defined)
     * @param name display name
     * @param description brief description
     * @param systemPrompt markdown written to CLAUDE.md
     * @param welcomeMessage first chat message (nullable)
     * @param workingDirectory absolute path or null for auto-created
     * @param mcpServers MCP server names to include
     * @param toolsets toolset names whose tools are auto-approved
     * @param allowedTools explicit tool patterns for --allowedTools
     * @param builtIn true if loaded from classpath resources
     */
    public record SessionTemplate(
            String templateId,
            String name,
            String description,
            String systemPrompt,
            String welcomeMessage,
            String workingDirectory,
            List<String> mcpServers,
            List<String> toolsets,
            List<String> allowedTools,
            boolean builtIn) {
    }

    /**
     * Loads built-in templates from classpath resources at application startup.
     *
     * @param event the startup event
     */
    void onStartup(@Observes StartupEvent event) {
        for (String fileName : BUILT_IN_FILES) {
            try (InputStream is = getClass().getClassLoader()
                    .getResourceAsStream(TEMPLATES_RESOURCE_DIR + fileName)) {
                if (is == null) {
                    LOG.warnf("Built-in template resource not found: %s", fileName);
                    continue;
                }
                JsonNode node = objectMapper.readTree(is);
                SessionTemplate template = parseTemplateJson(node, true);
                builtInTemplates.put(template.templateId(), template);
                LOG.infof("Loaded built-in template: %s", template.templateId());
            } catch (IOException e) {
                LOG.errorf(e, "Failed to load built-in template: %s", fileName);
            }
        }
    }

    /**
     * Returns all templates (built-in + user-defined), built-ins first.
     *
     * @return merged list of all templates
     */
    public List<SessionTemplate> listTemplates() {
        List<SessionTemplate> result = new ArrayList<>(builtInTemplates.values());
        List<SessionTemplateEntity> entities = SessionTemplateEntity.listAll();
        for (SessionTemplateEntity entity : entities) {
            result.add(toTemplate(entity));
        }
        return result;
    }

    /**
     * Returns a template by its template ID, checking built-ins first.
     *
     * @param templateId the template identifier
     * @return the template, or null if not found
     */
    public SessionTemplate getTemplate(String templateId) {
        SessionTemplate builtIn = builtInTemplates.get(templateId);
        if (builtIn != null) {
            return builtIn;
        }
        SessionTemplateEntity entity = SessionTemplateEntity
                .find("templateId", templateId).firstResult();
        return entity != null ? toTemplate(entity) : null;
    }

    /**
     * Returns whether the given template ID refers to a built-in template.
     *
     * @param templateId the template identifier
     * @return true if this is a built-in template
     */
    public boolean isBuiltIn(String templateId) {
        return builtInTemplates.containsKey(templateId);
    }

    /**
     * Creates a new user-defined template.
     *
     * @param template the template data (templateId is generated if not provided)
     * @return the persisted template
     */
    @Transactional
    public SessionTemplate createTemplate(SessionTemplate template) {
        SessionTemplateEntity entity = new SessionTemplateEntity();
        entity.templateId = template.templateId() != null && !template.templateId().isBlank()
                ? template.templateId()
                : UUID.randomUUID().toString();
        entity.name = template.name();
        entity.description = template.description();
        entity.systemPrompt = template.systemPrompt();
        entity.welcomeMessage = template.welcomeMessage();
        entity.workingDirectory = template.workingDirectory();
        entity.mcpServers = new ArrayList<>(template.mcpServers() != null
                ? template.mcpServers() : List.of());
        entity.toolsets = new ArrayList<>(template.toolsets() != null
                ? template.toolsets() : List.of());
        entity.allowedTools = new ArrayList<>(template.allowedTools() != null
                ? template.allowedTools() : List.of());
        entity.persist();
        return toTemplate(entity);
    }

    /**
     * Updates a user-defined template. Throws if the template is built-in.
     *
     * @param templateId the template identifier
     * @param template the updated template data
     * @return the updated template
     * @throws IllegalArgumentException if the template is not found
     * @throws IllegalStateException if the template is built-in
     */
    @Transactional
    public SessionTemplate updateTemplate(String templateId, SessionTemplate template) {
        if (isBuiltIn(templateId)) {
            throw new IllegalStateException("Cannot modify built-in template: " + templateId);
        }
        SessionTemplateEntity entity = SessionTemplateEntity
                .find("templateId", templateId).firstResult();
        if (entity == null) {
            throw new IllegalArgumentException("Template not found: " + templateId);
        }
        entity.name = template.name();
        entity.description = template.description();
        entity.systemPrompt = template.systemPrompt();
        entity.welcomeMessage = template.welcomeMessage();
        entity.workingDirectory = template.workingDirectory();
        entity.mcpServers = new ArrayList<>(template.mcpServers() != null
                ? template.mcpServers() : List.of());
        entity.toolsets = new ArrayList<>(template.toolsets() != null
                ? template.toolsets() : List.of());
        entity.allowedTools = new ArrayList<>(template.allowedTools() != null
                ? template.allowedTools() : List.of());
        entity.persist();
        return toTemplate(entity);
    }

    /**
     * Deletes a user-defined template. Throws if the template is built-in.
     *
     * @param templateId the template identifier
     * @throws IllegalStateException if the template is built-in
     */
    @Transactional
    public void deleteTemplate(String templateId) {
        if (isBuiltIn(templateId)) {
            throw new IllegalStateException("Cannot delete built-in template: " + templateId);
        }
        SessionTemplateEntity entity = SessionTemplateEntity
                .find("templateId", templateId).firstResult();
        if (entity != null) {
            entity.delete();
        }
    }

    private SessionTemplate toTemplate(SessionTemplateEntity entity) {
        return new SessionTemplate(
                entity.templateId,
                entity.name,
                entity.description,
                entity.systemPrompt,
                entity.welcomeMessage,
                entity.workingDirectory,
                List.copyOf(entity.mcpServers),
                List.copyOf(entity.toolsets),
                List.copyOf(entity.allowedTools),
                false);
    }

    private SessionTemplate parseTemplateJson(JsonNode node, boolean builtIn) {
        return new SessionTemplate(
                node.path("templateId").asText(),
                node.path("name").asText(),
                node.path("description").asText(""),
                node.path("systemPrompt").asText(""),
                node.path("welcomeMessage").asText(null),
                node.path("workingDirectory").asText(null),
                jsonArrayToList(node.path("mcpServers")),
                jsonArrayToList(node.path("toolsets")),
                jsonArrayToList(node.path("allowedTools")),
                builtIn);
    }

    private List<String> jsonArrayToList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                result.add(item.asText());
            }
        }
        return result;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json \
       app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java
git commit -m "Add SessionTemplateService with built-in config assistant template"
```

---

### Task 3: OpenAPI Spec and REST Resource for Templates

Add template schemas and endpoints to the OpenAPI spec, then create the JAX-RS REST resource. Also
update the `CreateAssistantSessionRequest` and `AssistantSessionInfo` schemas to include `templateId`.

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/SessionTemplateResource.java`

**Interfaces:**
- Consumes: `SessionTemplateService` and its `SessionTemplate` record (from Task 2)
- Produces:
  - REST endpoints: `GET/POST /api/v1/assistant/templates`,
    `GET/PUT/DELETE /api/v1/assistant/templates/{templateId}`
  - Updated `CreateAssistantSessionRequest` with required `templateId` field
  - Updated `AssistantSessionInfo` with `templateId` field

- [ ] **Step 1: Add template schemas to openapi.json**

Add the following schemas to the `components.schemas` section of `openapi.json` (after the existing
Assistant schemas):

```json
"SessionTemplate": {
  "required": ["templateId", "name", "description", "systemPrompt"],
  "type": "object",
  "properties": {
    "templateId": {
      "description": "Unique template identifier",
      "type": "string"
    },
    "name": {
      "description": "Display name",
      "type": "string"
    },
    "description": {
      "description": "Brief description shown in the template picker",
      "type": "string"
    },
    "builtIn": {
      "description": "Whether this is an immutable built-in template",
      "type": "boolean"
    },
    "systemPrompt": {
      "description": "Markdown content written to CLAUDE.md",
      "type": "string"
    },
    "welcomeMessage": {
      "description": "First message shown in the chat UI",
      "type": "string"
    },
    "workingDirectory": {
      "description": "Optional absolute path for the session working directory",
      "type": "string"
    },
    "mcpServers": {
      "description": "Names of MCP server configurations to include",
      "type": "array",
      "items": { "type": "string" }
    },
    "toolsets": {
      "description": "Names of toolsets whose tools are auto-approved",
      "type": "array",
      "items": { "type": "string" }
    },
    "allowedTools": {
      "description": "Additional explicit tool patterns for --allowedTools",
      "type": "array",
      "items": { "type": "string" }
    }
  }
},
"NewSessionTemplate": {
  "required": ["name", "description", "systemPrompt"],
  "type": "object",
  "properties": {
    "templateId": {
      "description": "Optional custom template ID (generated if omitted)",
      "type": "string"
    },
    "name": {
      "description": "Display name",
      "type": "string"
    },
    "description": {
      "description": "Brief description",
      "type": "string"
    },
    "systemPrompt": {
      "description": "Markdown content written to CLAUDE.md",
      "type": "string"
    },
    "welcomeMessage": {
      "description": "First message shown in the chat UI",
      "type": "string"
    },
    "workingDirectory": {
      "description": "Optional absolute path for the session working directory",
      "type": "string"
    },
    "mcpServers": {
      "description": "Names of MCP server configurations to include",
      "type": "array",
      "items": { "type": "string" }
    },
    "toolsets": {
      "description": "Names of toolsets whose tools are auto-approved",
      "type": "array",
      "items": { "type": "string" }
    },
    "allowedTools": {
      "description": "Additional explicit tool patterns for --allowedTools",
      "type": "array",
      "items": { "type": "string" }
    }
  }
},
"SessionTemplateList": {
  "type": "array",
  "items": {
    "$ref": "#/components/schemas/SessionTemplate"
  }
}
```

- [ ] **Step 2: Update existing Assistant schemas in openapi.json**

Add `templateId` to `CreateAssistantSessionRequest`:

```json
"CreateAssistantSessionRequest": {
  "required": ["templateId"],
  "type": "object",
  "properties": {
    "name": {
      "description": "Optional session name",
      "type": "string"
    },
    "templateId": {
      "description": "Template ID to create the session from",
      "type": "string"
    }
  }
}
```

Add `templateId` to `AssistantSessionInfo` properties (and add it to the `required` array):

```json
"templateId": {
  "description": "Template ID this session was created from",
  "type": "string"
}
```

- [ ] **Step 3: Add template path operations to openapi.json**

Add the following paths to the `paths` section (before or after the existing `/assistant/sessions`
paths):

```json
"/assistant/templates": {
  "get": {
    "tags": ["Assistant"],
    "summary": "List all session templates",
    "operationId": "listSessionTemplates",
    "responses": {
      "200": {
        "description": "List of session templates",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/SessionTemplateList"
            }
          }
        }
      }
    }
  },
  "post": {
    "tags": ["Assistant"],
    "summary": "Create a user-defined session template",
    "operationId": "createSessionTemplate",
    "requestBody": {
      "content": {
        "application/json": {
          "schema": {
            "$ref": "#/components/schemas/NewSessionTemplate"
          }
        }
      }
    },
    "responses": {
      "201": {
        "description": "Template created",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/SessionTemplate"
            }
          }
        }
      }
    }
  }
},
"/assistant/templates/{templateId}": {
  "get": {
    "tags": ["Assistant"],
    "summary": "Get a session template",
    "operationId": "getSessionTemplate",
    "responses": {
      "200": {
        "description": "Session template details",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/SessionTemplate"
            }
          }
        }
      }
    },
    "parameters": [
      {
        "name": "templateId",
        "in": "path",
        "required": true,
        "schema": { "type": "string" },
        "description": "The template identifier"
      }
    ]
  },
  "put": {
    "tags": ["Assistant"],
    "summary": "Update a user-defined session template",
    "operationId": "updateSessionTemplate",
    "requestBody": {
      "content": {
        "application/json": {
          "schema": {
            "$ref": "#/components/schemas/NewSessionTemplate"
          }
        }
      }
    },
    "responses": {
      "200": {
        "description": "Template updated",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/SessionTemplate"
            }
          }
        }
      },
      "403": {
        "description": "Cannot modify built-in template"
      }
    },
    "parameters": [
      {
        "name": "templateId",
        "in": "path",
        "required": true,
        "schema": { "type": "string" },
        "description": "The template identifier"
      }
    ]
  },
  "delete": {
    "tags": ["Assistant"],
    "summary": "Delete a user-defined session template",
    "operationId": "deleteSessionTemplate",
    "responses": {
      "204": {
        "description": "Template deleted"
      },
      "403": {
        "description": "Cannot delete built-in template"
      }
    },
    "parameters": [
      {
        "name": "templateId",
        "in": "path",
        "required": true,
        "schema": { "type": "string" },
        "description": "The template identifier"
      }
    ]
  }
}
```

- [ ] **Step 4: Create SessionTemplateResource**

Create `app/src/main/java/io/apitomy/axiom/app/rest/SessionTemplateResource.java`:

```java
package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.SessionTemplateService;
import io.apitomy.axiom.app.assistant.SessionTemplateService.SessionTemplate;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * REST resource for managing AI Assistant session templates.
 */
@Path("/api/v1/assistant/templates")
@ApplicationScoped
@io.smallrye.common.annotation.RunOnVirtualThread
public class SessionTemplateResource {

    private static final Logger LOG = Logger.getLogger(SessionTemplateResource.class);

    @Inject
    SessionTemplateService templateService;

    @Inject
    ObjectMapper objectMapper;

    /** Lists all session templates (built-in + user-defined). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTemplates() {
        List<SessionTemplate> templates = templateService.listTemplates();
        ArrayNode array = objectMapper.createArrayNode();
        for (SessionTemplate template : templates) {
            array.add(toJson(template));
        }
        return Response.ok(array).build();
    }

    /** Gets a session template by its template ID. */
    @GET
    @Path("/{templateId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTemplate(@PathParam("templateId") String templateId) {
        SessionTemplate template = templateService.getTemplate(templateId);
        if (template == null) {
            return errorResponse(404, "Template not found: " + templateId);
        }
        return Response.ok(toJson(template)).build();
    }

    /** Creates a new user-defined session template. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createTemplate(JsonNode body) {
        try {
            SessionTemplate input = fromJson(body);
            SessionTemplate created = templateService.createTemplate(input);
            return Response.status(Response.Status.CREATED)
                    .entity(toJson(created)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create template");
            return errorResponse(500, "Failed to create template: " + e.getMessage());
        }
    }

    /** Updates a user-defined session template. Returns 403 for built-ins. */
    @PUT
    @Path("/{templateId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateTemplate(@PathParam("templateId") String templateId,
                                    JsonNode body) {
        try {
            SessionTemplate input = fromJson(body);
            SessionTemplate updated = templateService.updateTemplate(templateId, input);
            return Response.ok(toJson(updated)).build();
        } catch (IllegalStateException e) {
            return errorResponse(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update template %s", templateId);
            return errorResponse(500, "Failed to update template: " + e.getMessage());
        }
    }

    /** Deletes a user-defined session template. Returns 403 for built-ins. */
    @DELETE
    @Path("/{templateId}")
    public Response deleteTemplate(@PathParam("templateId") String templateId) {
        try {
            templateService.deleteTemplate(templateId);
            return Response.noContent().build();
        } catch (IllegalStateException e) {
            return errorResponse(403, e.getMessage());
        }
    }

    private ObjectNode toJson(SessionTemplate template) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("templateId", template.templateId());
        node.put("name", template.name());
        node.put("description", template.description());
        node.put("builtIn", template.builtIn());
        node.put("systemPrompt", template.systemPrompt());
        if (template.welcomeMessage() != null) {
            node.put("welcomeMessage", template.welcomeMessage());
        }
        if (template.workingDirectory() != null) {
            node.put("workingDirectory", template.workingDirectory());
        }
        node.set("mcpServers", toJsonArray(template.mcpServers()));
        node.set("toolsets", toJsonArray(template.toolsets()));
        node.set("allowedTools", toJsonArray(template.allowedTools()));
        return node;
    }

    private SessionTemplate fromJson(JsonNode body) {
        return new SessionTemplate(
                body.path("templateId").asText(null),
                body.path("name").asText(""),
                body.path("description").asText(""),
                body.path("systemPrompt").asText(""),
                body.path("welcomeMessage").asText(null),
                body.path("workingDirectory").asText(null),
                jsonArrayToList(body.path("mcpServers")),
                jsonArrayToList(body.path("toolsets")),
                jsonArrayToList(body.path("allowedTools")),
                false);
    }

    private ArrayNode toJsonArray(List<String> items) {
        ArrayNode array = objectMapper.createArrayNode();
        if (items != null) {
            items.forEach(array::add);
        }
        return array;
    }

    private List<String> jsonArrayToList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private Response errorResponse(int status, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("message", message);
        return Response.status(status).entity(error).build();
    }
}
```

- [ ] **Step 5: Regenerate API beans**

Run the project's code generation to produce the updated Java beans from the OpenAPI spec. The user
handles this step (maven build).

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json \
       app/src/main/java/io/apitomy/axiom/app/rest/SessionTemplateResource.java
git commit -m "Add REST API and OpenAPI spec for session templates"
```

---

### Task 4: Wire Templates into Session Lifecycle

Update `AssistantSession`, `AssistantSessionManager`, and `AssistantContextBuilder` to be template-driven
instead of hardcoded. The Config Assistant's sidebar, validation, and apply logic become conditional on
`templateId === "axiom-config-assistant"`.

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantContextBuilder.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantSessionResource.java`

**Interfaces:**
- Consumes: `SessionTemplateService` and `SessionTemplate` (from Task 2),
  `McpServerEntity`, `ToolsetEntity`
- Produces: Updated `AssistantSession` with `getTemplateId()` method. Updated
  `AssistantSessionManager.createSession(String name, String templateId)`. Updated
  `AssistantSessionResource` that reads `templateId` from request and includes it in responses.

- [ ] **Step 1: Add templateId to AssistantSession**

In `AssistantSession.java`, add a `templateId` field and update the constructor:

```java
private final String templateId;
```

Update the constructor signature to:
```java
public AssistantSession(String name, String templateId, Path workingDirectory,
                         List<String> command)
```

And store `this.templateId = templateId;`.

Add a getter:
```java
/** Returns the template ID this session was created from. */
public String getTemplateId() {
    return templateId;
}
```

- [ ] **Step 2: Update AssistantContextBuilder to be template-driven**

Replace the hardcoded `buildClaudeMd()` method with a method that takes the system prompt as a
parameter:

```java
/**
 * Creates the full working directory for an assistant session.
 *
 * @param sessionId the unique session identifier
 * @param templateId the template this session was created from
 * @param systemPrompt markdown content to write to CLAUDE.md
 * @param mcpConfig the MCP configuration JSON string, or null
 * @return the path to the created working directory
 * @throws IOException if directory creation fails
 */
public Path createWorkingDirectory(String sessionId, String templateId,
                                    String systemPrompt, String mcpConfig)
        throws IOException {
    Path axiomHome = Path.of(System.getProperty("user.home"), ".axiom");
    Path sessionsRoot = axiomHome.resolve("assistant-sessions");
    Path sessionDir = sessionsRoot.resolve(sessionId);

    Files.createDirectories(sessionDir);

    // Config Assistant needs item subdirectories
    if ("axiom-config-assistant".equals(templateId)) {
        Files.createDirectories(sessionDir.resolve("tools"));
        Files.createDirectories(sessionDir.resolve("action-types"));
        Files.createDirectories(sessionDir.resolve("report-definitions"));
    }

    Files.writeString(sessionDir.resolve("CLAUDE.md"), systemPrompt);

    if (mcpConfig != null) {
        Files.writeString(sessionDir.resolve("mcp-config.json"), mcpConfig);
    }

    LOG.infof("Created assistant working directory: %s", sessionDir);
    return sessionDir;
}
```

Remove the old `buildClaudeMd()` and `buildMcpConfig(Path)` private methods. Add a new generalized
MCP config builder:

```java
/**
 * Builds an MCP configuration JSON string from resolved MCP server entities
 * and optional built-in server entries.
 *
 * @param resolvedServers map of server name to MCP server config objects
 * @return the JSON config string
 */
public String buildMcpConfig(java.util.Map<String, McpServerConfig> resolvedServers) {
    if (resolvedServers.isEmpty()) {
        return null;
    }
    ObjectNode root = objectMapper.createObjectNode();
    ObjectNode servers = root.putObject("mcpServers");
    for (var entry : resolvedServers.entrySet()) {
        ObjectNode serverNode = servers.putObject(entry.getKey());
        McpServerConfig config = entry.getValue();
        serverNode.put("command", config.command());
        ArrayNode argsNode = serverNode.putArray("args");
        config.args().forEach(argsNode::add);
        if (!config.env().isEmpty()) {
            ObjectNode envNode = serverNode.putObject("env");
            config.env().forEach(envNode::put);
        }
    }
    return root.toPrettyString();
}

/**
 * Resolved MCP server configuration ready for the mcp-config.json file.
 */
public record McpServerConfig(String command, List<String> args,
                                Map<String, String> env) {
}
```

Inject `ObjectMapper` into the class:
```java
@Inject
ObjectMapper objectMapper;
```

- [ ] **Step 3: Update AssistantSessionManager to use templates**

Inject the new service:
```java
@Inject
SessionTemplateService templateService;
```

Update `createSession` to accept and use a template ID:

```java
public AssistantSession createSession(String name, String templateId) throws IOException {
    if (!"claude-code".equals(aiEngine)) {
        throw new IllegalStateException(
                "The AI Assistant requires Claude Code as the active AI engine. "
                        + "Current engine: " + aiEngine);
    }

    if (sessions.size() >= maxSessions) {
        throw new SessionLimitReachedException(
                "Maximum number of assistant sessions reached (" + maxSessions + ")");
    }

    SessionTemplateService.SessionTemplate template = templateService.getTemplate(templateId);
    if (template == null) {
        throw new IllegalArgumentException("Template not found: " + templateId);
    }

    // Resolve MCP servers from template
    Map<String, AssistantContextBuilder.McpServerConfig> mcpConfigs =
            resolveMcpServers(template);

    // Resolve allowed tools from template
    List<String> resolvedAllowedTools = resolveAllowedTools(template);

    // Build MCP config JSON
    String mcpConfig = contextBuilder.buildMcpConfig(mcpConfigs);

    // Determine working directory
    Path workDir;
    if (template.workingDirectory() != null && !template.workingDirectory().isBlank()) {
        workDir = Path.of(template.workingDirectory());
        if (!Files.isDirectory(workDir)) {
            throw new IOException("Template working directory does not exist: "
                    + template.workingDirectory());
        }
        // Still write CLAUDE.md and mcp-config.json into the working directory
        Files.writeString(workDir.resolve("CLAUDE.md"), template.systemPrompt());
        if (mcpConfig != null) {
            Files.writeString(workDir.resolve("mcp-config.json"), mcpConfig);
        }
    } else {
        workDir = contextBuilder.createWorkingDirectory(
                UUID.randomUUID().toString(), templateId,
                template.systemPrompt(), mcpConfig);
    }

    List<String> command = buildCommand(workDir, resolvedAllowedTools, mcpConfig != null);

    String sessionName = name != null && !name.isBlank() ? name : "Assistant Session";
    AssistantSession session = new AssistantSession(sessionName, templateId, workDir, command);
    session.start();

    // Config Assistant validation listener
    if ("axiom-config-assistant".equals(templateId)) {
        session.addListener(createValidationListener(session));
    }

    sessions.put(session.getId(), session);
    LOG.infof("Created assistant session %s (%s) from template %s",
            session.getId(), sessionName, templateId);
    return session;
}
```

Update `buildCommand` to take resolved allowed tools:

```java
private List<String> buildCommand(Path workDir, List<String> allowedTools,
                                   boolean hasMcpConfig) {
    List<String> cmd = new ArrayList<>();
    cmd.add(claudeExecutable);
    cmd.add("--print");
    cmd.add("--input-format");
    cmd.add("stream-json");
    cmd.add("--output-format");
    cmd.add("stream-json");
    cmd.add("--verbose");
    cmd.add("--permission-prompt-tool");
    cmd.add("stdio");

    if (hasMcpConfig) {
        cmd.add("--mcp-config");
        cmd.add(workDir.resolve("mcp-config.json").toAbsolutePath().toString());
    }

    if (!allowedTools.isEmpty()) {
        cmd.add("--allowedTools");
        cmd.add(String.join(" ", allowedTools));
    }

    return cmd;
}
```

Add helper methods for resolving MCP servers and allowed tools from template references:

```java
private Map<String, AssistantContextBuilder.McpServerConfig> resolveMcpServers(
        SessionTemplateService.SessionTemplate template) throws IOException {
    Map<String, AssistantContextBuilder.McpServerConfig> configs = new LinkedHashMap<>();

    for (String serverName : template.mcpServers()) {
        if ("@axiom-assistant".equals(serverName)) {
            // Special built-in MCP server for the Config Assistant
            Path mcpServerDir = ensureAssistantMcpServerInstalled();
            String serverJsPath = mcpServerDir.resolve("server.js")
                    .toAbsolutePath().toString();
            String apiUrl = "http://localhost:" + httpPort + "/api/v1";
            configs.put("axiom", new AssistantContextBuilder.McpServerConfig(
                    "node",
                    List.of(serverJsPath),
                    Map.of("AXIOM_API_URL", apiUrl)));
        } else {
            // Resolve from database
            McpServerEntity entity = McpServerEntity.find("name", serverName).firstResult();
            if (entity != null && entity.serverCommand != null) {
                List<String> args = new ArrayList<>();
                if (entity.serverArgs != null && !entity.serverArgs.isBlank()) {
                    JsonNode argsNode = objectMapper.readTree(entity.serverArgs);
                    if (argsNode.isArray()) {
                        for (JsonNode arg : argsNode) {
                            args.add(arg.asText());
                        }
                    }
                }
                Map<String, String> env = new LinkedHashMap<>();
                if (entity.serverEnv != null && !entity.serverEnv.isBlank()) {
                    JsonNode envNode = objectMapper.readTree(entity.serverEnv);
                    envNode.fields().forEachRemaining(
                            field -> env.put(field.getKey(), field.getValue().asText()));
                }
                configs.put(serverName, new AssistantContextBuilder.McpServerConfig(
                        entity.serverCommand, args, env));
            } else {
                LOG.warnf("MCP server not found or has no command: %s", serverName);
            }
        }
    }

    return configs;
}

private List<String> resolveAllowedTools(
        SessionTemplateService.SessionTemplate template) {
    List<String> resolved = new ArrayList<>();

    // Add explicit allowed tools from the template
    resolved.addAll(template.allowedTools());

    // Resolve toolset references to their tool lists
    for (String toolsetName : template.toolsets()) {
        ToolsetEntity toolset = ToolsetEntity.find("name", toolsetName).firstResult();
        if (toolset != null && toolset.tools != null) {
            // Toolset tools are stored as comma-separated string
            String[] tools = toolset.tools.split(",");
            for (String tool : tools) {
                String trimmed = tool.trim();
                if (!trimmed.isEmpty()) {
                    resolved.add(trimmed);
                }
            }
        } else {
            LOG.warnf("Toolset not found: %s", toolsetName);
        }
    }

    return resolved;
}
```

Add the needed imports to `AssistantSessionManager.java`:
```java
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
```

- [ ] **Step 4: Update AssistantSessionResource for templateId**

In `AssistantSessionResource.java`, update the `createSession` method to read `templateId` from the
request body and pass it to `sessionManager.createSession(name, templateId)`:

```java
@POST
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public Response createSession(JsonNode body) {
    String name = body != null ? body.path("name").asText(null) : null;
    String templateId = body != null ? body.path("templateId").asText(null) : null;

    if (templateId == null || templateId.isBlank()) {
        return errorResponse(400, "Missing required 'templateId' field");
    }

    try {
        AssistantSession session = sessionManager.createSession(name, templateId);
        return Response.ok(toSessionInfo(session)).build();
    } catch (IllegalArgumentException e) {
        return errorResponse(404, e.getMessage());
    } catch (AssistantSessionManager.SessionLimitReachedException e) {
        return errorResponse(409, e.getMessage());
    } catch (Exception e) {
        LOG.errorf(e, "Failed to create assistant session");
        return errorResponse(500, "Failed to create session: " + e.getMessage());
    }
}
```

Update `toSessionInfo` to include `templateId`:
```java
private ObjectNode toSessionInfo(AssistantSession session) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("id", session.getId());
    node.put("name", session.getName());
    node.put("templateId", session.getTemplateId());
    node.put("status", session.getStatus().name().toLowerCase());
    node.put("createdAt", session.getCreatedAt().toString());
    node.put("lastActivityAt", session.getLastActivityAt().toString());
    String errorMsg = session.getErrorMessage();
    if (errorMsg != null) {
        node.put("errorMessage", errorMsg);
    }
    return node;
}
```

Make the items, item content, and apply endpoints conditional on the Config Assistant template:

For `listItems`, `getItemContent`, and `applySession` — add a guard at the top of each method:

```java
AssistantSession session = sessionManager.getSession(id);
if (session == null) {
    return errorResponse(404, "Session not found: " + id);
}
if (!"axiom-config-assistant".equals(session.getTemplateId())) {
    return errorResponse(400, "Items are only available for Configuration Assistant sessions");
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java \
       app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java \
       app/src/main/java/io/apitomy/axiom/app/assistant/AssistantContextBuilder.java \
       app/src/main/java/io/apitomy/axiom/app/rest/AssistantSessionResource.java
git commit -m "Wire session templates into assistant session lifecycle"
```

---

### Task 5: Frontend API Client and Types

Add TypeScript types and API client functions for session templates. Update the existing session
creation function to include `templateId`.

**Files:**
- Modify: `ui/src/config/api.ts`

**Interfaces:**
- Consumes: REST endpoints from Tasks 3 and 4
- Produces: TypeScript types `SessionTemplate`, `NewSessionTemplate` and functions
  `fetchAssistantTemplates()`, `fetchAssistantTemplate(templateId)`,
  `createAssistantTemplate(data)`, `updateAssistantTemplate(templateId, data)`,
  `deleteAssistantTemplate(templateId)`. Updated `AssistantSessionInfo` with `templateId` field.
  Updated `createAssistantSession(name, templateId)`.

- [ ] **Step 1: Add SessionTemplate types to api.ts**

Add these types near the existing `AssistantSessionInfo` type:

```typescript
export interface SessionTemplate {
    templateId: string;
    name: string;
    description: string;
    builtIn: boolean;
    systemPrompt: string;
    welcomeMessage?: string;
    workingDirectory?: string;
    mcpServers: string[];
    toolsets: string[];
    allowedTools: string[];
}

export interface NewSessionTemplate {
    templateId?: string;
    name: string;
    description: string;
    systemPrompt: string;
    welcomeMessage?: string;
    workingDirectory?: string;
    mcpServers?: string[];
    toolsets?: string[];
    allowedTools?: string[];
}
```

- [ ] **Step 2: Add templateId to AssistantSessionInfo**

```typescript
export interface AssistantSessionInfo {
    id: string;
    name: string;
    templateId: string;  // <-- add this field
    status: "starting" | "running" | "stopped" | "error";
    createdAt: string;
    lastActivityAt: string;
    errorMessage?: string;
}
```

- [ ] **Step 3: Add template CRUD functions**

Add after the existing assistant API functions:

```typescript
export async function fetchAssistantTemplates(): Promise<SessionTemplate[]> {
    const response = await fetch(`${API}/assistant/templates`);
    if (!response.ok) throw new Error("Failed to fetch templates");
    return response.json();
}

export async function fetchAssistantTemplate(templateId: string): Promise<SessionTemplate> {
    const response = await fetch(`${API}/assistant/templates/${encodeURIComponent(templateId)}`);
    if (!response.ok) throw new Error("Failed to fetch template");
    return response.json();
}

export async function createAssistantTemplate(
    data: NewSessionTemplate
): Promise<SessionTemplate> {
    const response = await fetch(`${API}/assistant/templates`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error("Failed to create template");
    return response.json();
}

export async function updateAssistantTemplate(
    templateId: string,
    data: NewSessionTemplate
): Promise<SessionTemplate> {
    const response = await fetch(
        `${API}/assistant/templates/${encodeURIComponent(templateId)}`,
        {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data),
        }
    );
    if (!response.ok) {
        if (response.status === 403) throw new Error("Cannot modify built-in template");
        throw new Error("Failed to update template");
    }
    return response.json();
}

export async function deleteAssistantTemplate(templateId: string): Promise<void> {
    const response = await fetch(
        `${API}/assistant/templates/${encodeURIComponent(templateId)}`,
        { method: "DELETE" }
    );
    if (!response.ok) {
        if (response.status === 403) throw new Error("Cannot delete built-in template");
        throw new Error("Failed to delete template");
    }
}
```

- [ ] **Step 4: Update createAssistantSession to include templateId**

Update the existing function signature and body:

```typescript
export async function createAssistantSession(
    templateId: string,
    name?: string
): Promise<AssistantSessionInfo> {
    const response = await fetch(`${API}/assistant/sessions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, templateId }),
    });
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        const message = body?.message || `Failed to create session (${response.status})`;
        throw new Error(message);
    }
    return response.json();
}
```

Note: The parameter order changes from `(name?)` to `(templateId, name?)` — all call sites will be
updated in Task 7.

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/api.ts
git commit -m "Add frontend API client for session templates"
```

---

### Task 6: Template Management Pages (Configuration > AI Assistant)

Create the template list page and template detail/edit page in the Configuration section, add the
sidebar navigation item, and add routes.

**Files:**
- Create: `ui/src/pages/SessionTemplatesPage.tsx`
- Create: `ui/src/pages/SessionTemplateDetailPage.tsx`
- Modify: `ui/src/components/AppSidebar.tsx`
- Modify: `ui/src/App.tsx`

**Interfaces:**
- Consumes: API functions from Task 5 (`fetchAssistantTemplates`, `createAssistantTemplate`,
  `deleteAssistantTemplate`, `fetchAssistantTemplate`, `updateAssistantTemplate`,
  `fetchMcpServers`, `fetchToolsets`), types `SessionTemplate`, `NewSessionTemplate`,
  `McpServer`, `Toolset`
- Produces: Two new route pages accessible at `/session-templates` and
  `/session-templates/:templateId`

- [ ] **Step 1: Create SessionTemplatesPage (list page)**

Create `ui/src/pages/SessionTemplatesPage.tsx`:

```tsx
import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    Flex,
    FlexItem,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Spinner,
    TextInput,
    EmptyState,
    EmptyStateBody,
    EmptyStateFooter,
    Form,
    FormGroup,
} from "@patternfly/react-core";
import { Table, Thead, Tr, Th, Tbody, Td } from "@patternfly/react-table";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import CopyIcon from "@patternfly/react-icons/dist/esm/icons/copy-icon";
import RobotIcon from "@patternfly/react-icons/dist/esm/icons/robot-icon";
import {
    fetchAssistantTemplates,
    createAssistantTemplate,
    deleteAssistantTemplate,
    type SessionTemplate,
} from "../config/api";

export function SessionTemplatesPage() {
    const navigate = useNavigate();
    const [templates, setTemplates] = useState<SessionTemplate[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [newName, setNewName] = useState("");
    const [creating, setCreating] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

    const load = useCallback(() => {
        setLoading(true);
        fetchAssistantTemplates()
            .then(setTemplates)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleCreate = () => {
        if (!newName.trim()) return;
        setCreating(true);
        createAssistantTemplate({
            name: newName.trim(),
            description: "",
            systemPrompt: "# Assistant\n\nYou are a helpful AI assistant.",
        })
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                navigate(`/session-templates/${created.templateId}`);
            })
            .catch(console.error)
            .finally(() => setCreating(false));
    };

    const handleClone = (template: SessionTemplate) => {
        createAssistantTemplate({
            name: template.name + " (Copy)",
            description: template.description,
            systemPrompt: template.systemPrompt,
            welcomeMessage: template.welcomeMessage,
            workingDirectory: template.workingDirectory,
            mcpServers: template.mcpServers,
            toolsets: template.toolsets,
            allowedTools: template.allowedTools,
        })
            .then((created) => navigate(`/session-templates/${created.templateId}`))
            .catch(console.error);
    };

    const handleDelete = () => {
        if (!deleteTarget) return;
        deleteAssistantTemplate(deleteTarget)
            .then(() => {
                setDeleteTarget(null);
                load();
            })
            .catch(console.error);
    };

    if (loading) {
        return <PageSection><Spinner size="lg" /></PageSection>;
    }

    return (
        <PageSection>
            <Flex style={{ marginBottom: 16 }}>
                <FlexItem grow={{ default: "grow" }}>
                    <span style={{ fontSize: "24px", fontWeight: 600 }}>
                        AI Assistant Templates
                    </span>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" onClick={() => setIsCreateOpen(true)}>
                        Create Template
                    </Button>
                </FlexItem>
            </Flex>

            {templates.length === 0 ? (
                <EmptyState headingLevel="h2" icon={RobotIcon}
                    titleText="No templates">
                    <EmptyStateBody>
                        Create a session template to configure AI Assistant sessions
                        for different tasks.
                    </EmptyStateBody>
                    <EmptyStateFooter>
                        <Button variant="primary"
                            onClick={() => setIsCreateOpen(true)}>
                            Create Template
                        </Button>
                    </EmptyStateFooter>
                </EmptyState>
            ) : (
                <Table aria-label="Session templates" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Name</Th>
                            <Th>Description</Th>
                            <Th>Type</Th>
                            <Th width={15}>Actions</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {templates.map((t) => (
                            <Tr key={t.templateId} isClickable
                                onRowClick={() =>
                                    navigate(`/session-templates/${t.templateId}`)
                                }>
                                <Td>{t.name}</Td>
                                <Td>{t.description}</Td>
                                <Td>
                                    <Label color={t.builtIn ? "blue" : "green"}>
                                        {t.builtIn ? "Built-in" : "Custom"}
                                    </Label>
                                </Td>
                                <Td>
                                    {t.builtIn ? (
                                        <Button variant="plain" size="sm"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                handleClone(t);
                                            }}>
                                            <CopyIcon />
                                        </Button>
                                    ) : (
                                        <Button variant="plain" size="sm"
                                            isDanger
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                setDeleteTarget(t.templateId);
                                            }}>
                                            <TrashIcon />
                                        </Button>
                                    )}
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}

            {/* Create modal */}
            <Modal isOpen={isCreateOpen}
                onClose={() => setIsCreateOpen(false)}
                variant="small" aria-label="Create template">
                <ModalHeader title="Create Template" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" value={newName}
                                onChange={(_e, v) => setNewName(v)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") handleCreate();
                                }} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreate}
                        isDisabled={!newName.trim() || creating}
                        isLoading={creating}>
                        Create
                    </Button>
                    <Button variant="link"
                        onClick={() => setIsCreateOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            {/* Delete confirmation */}
            <Modal isOpen={deleteTarget !== null}
                onClose={() => setDeleteTarget(null)}
                variant="small" aria-label="Confirm delete">
                <ModalHeader title="Delete Template?" />
                <ModalBody>
                    Are you sure you want to delete this template? This cannot be undone.
                </ModalBody>
                <ModalFooter>
                    <Button variant="danger" onClick={handleDelete}>Delete</Button>
                    <Button variant="link"
                        onClick={() => setDeleteTarget(null)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
```

- [ ] **Step 2: Create SessionTemplateDetailPage (detail/edit page)**

Create `ui/src/pages/SessionTemplateDetailPage.tsx`:

```tsx
import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Alert,
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    Label,
    PageSection,
    Spinner,
    TextArea,
    TextInput,
    CodeEditor,
    Language,
} from "@patternfly/react-core";
import { DualListSelector } from "@patternfly/react-core";
import {
    fetchAssistantTemplate,
    updateAssistantTemplate,
    fetchMcpServers,
    fetchToolsets,
    type SessionTemplate,
    type NewSessionTemplate,
    type McpServer,
    type Toolset,
} from "../config/api";

export function SessionTemplateDetailPage() {
    const { templateId } = useParams<{ templateId: string }>();
    const [template, setTemplate] = useState<SessionTemplate | null>(null);
    const [form, setForm] = useState<NewSessionTemplate>({
        name: "", description: "", systemPrompt: "",
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [mcpServers, setMcpServers] = useState<McpServer[]>([]);
    const [toolsets, setToolsets] = useState<Toolset[]>([]);

    const loadData = useCallback(() => {
        if (!templateId) return;
        setLoading(true);
        Promise.all([
            fetchAssistantTemplate(templateId),
            fetchMcpServers(),
            fetchToolsets(),
        ])
            .then(([t, servers, ts]) => {
                setTemplate(t);
                setForm({
                    name: t.name,
                    description: t.description,
                    systemPrompt: t.systemPrompt,
                    welcomeMessage: t.welcomeMessage,
                    workingDirectory: t.workingDirectory,
                    mcpServers: t.mcpServers,
                    toolsets: t.toolsets,
                    allowedTools: t.allowedTools,
                });
                setMcpServers(servers);
                setToolsets(ts);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [templateId]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<NewSessionTemplate>) => {
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const handleSave = () => {
        if (!templateId) return;
        setSaving(true);
        updateAssistantTemplate(templateId, form)
            .then((updated) => {
                setTemplate(updated);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    if (loading) {
        return <PageSection><Spinner size="lg" /></PageSection>;
    }

    if (!template) {
        return (
            <PageSection>
                <Alert variant="danger" isInline title="Template not found" />
            </PageSection>
        );
    }

    const isReadOnly = template.builtIn;

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: 16 }}>
                <BreadcrumbItem>
                    <Link to="/session-templates">AI Assistant Templates</Link>
                </BreadcrumbItem>
                <BreadcrumbItem isActive>{template.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex style={{ marginBottom: 16 }}>
                <FlexItem grow={{ default: "grow" }}>
                    <span style={{ fontSize: "24px", fontWeight: 600 }}>
                        {template.name}
                    </span>
                    {isReadOnly && (
                        <Label color="blue" style={{ marginLeft: 12 }}>
                            Built-in (read-only)
                        </Label>
                    )}
                </FlexItem>
                {!isReadOnly && (
                    <FlexItem>
                        <Button variant="primary" onClick={handleSave}
                            isDisabled={!dirty || !form.name || saving}
                            isLoading={saving}>
                            Save
                        </Button>
                    </FlexItem>
                )}
            </Flex>

            <Form>
                <FormGroup label="Name" isRequired fieldId="name">
                    <TextInput id="name" value={form.name}
                        onChange={(_e, v) => updateForm({ name: v })}
                        isDisabled={isReadOnly} />
                </FormGroup>

                <FormGroup label="Description" isRequired fieldId="description">
                    <TextArea id="description" value={form.description}
                        onChange={(_e, v) => updateForm({ description: v })}
                        isDisabled={isReadOnly} rows={2} />
                </FormGroup>

                <FormGroup label="System Prompt" isRequired fieldId="systemPrompt">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Markdown content written to CLAUDE.md in the session working
                                directory.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <CodeEditor
                        code={form.systemPrompt}
                        onChange={(_e, v) => updateForm({ systemPrompt: v })}
                        language={Language.markdown}
                        height="300px"
                        isReadOnly={isReadOnly}
                    />
                </FormGroup>

                <FormGroup label="Welcome Message" fieldId="welcomeMessage">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                First message shown in the chat UI (supports markdown).
                                Leave empty for no welcome message.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <TextArea id="welcomeMessage"
                        value={form.welcomeMessage || ""}
                        onChange={(_e, v) =>
                            updateForm({ welcomeMessage: v || undefined })
                        }
                        isDisabled={isReadOnly} rows={4} />
                </FormGroup>

                <FormGroup label="Working Directory" fieldId="workingDirectory">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Optional absolute path. If empty, Axiom creates a temporary
                                directory.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <TextInput id="workingDirectory"
                        value={form.workingDirectory || ""}
                        onChange={(_e, v) =>
                            updateForm({ workingDirectory: v || undefined })
                        }
                        isDisabled={isReadOnly} />
                </FormGroup>

                <FormGroup label="MCP Servers" fieldId="mcpServers">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Select MCP servers to include in the session. These are
                                resolved from your configured MCP servers.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <DualListSelector
                        availableOptions={mcpServers
                            .map((s) => s.name)
                            .filter((n) => !(form.mcpServers || []).includes(n))}
                        chosenOptions={form.mcpServers || []}
                        onListChange={(_e, newAvail, newChosen) =>
                            updateForm({ mcpServers: newChosen as string[] })
                        }
                        isDisabled={isReadOnly}
                    />
                </FormGroup>

                <FormGroup label="Toolsets" fieldId="toolsets">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Select toolsets whose tools are automatically approved.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <DualListSelector
                        availableOptions={toolsets
                            .map((t) => t.name)
                            .filter((n) => !(form.toolsets || []).includes(n))}
                        chosenOptions={form.toolsets || []}
                        onListChange={(_e, newAvail, newChosen) =>
                            updateForm({ toolsets: newChosen as string[] })
                        }
                        isDisabled={isReadOnly}
                    />
                </FormGroup>

                <FormGroup label="Allowed Tools" fieldId="allowedTools">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Additional tool patterns (one per line, e.g. "Read(*)",
                                "Bash(ls *)").
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <TextArea id="allowedTools"
                        value={(form.allowedTools || []).join("\n")}
                        onChange={(_e, v) => updateForm({
                            allowedTools: v.split("\n")
                                .map((s) => s.trim())
                                .filter((s) => s.length > 0),
                        })}
                        isDisabled={isReadOnly} rows={4} />
                </FormGroup>
            </Form>
        </PageSection>
    );
}
```

- [ ] **Step 3: Add sidebar navigation and routes**

In `ui/src/components/AppSidebar.tsx`:

Add `"/session-templates"` to the `CONFIG_PATHS` array.

Add a new `NavItem` inside the Configuration `NavExpandable`, in alphabetical position (after
"AI Engine"):

```tsx
<NavItem isActive={location.pathname.startsWith("/session-templates")}
    onClick={() => navigate("/session-templates")}>
    AI Assistant
</NavItem>
```

In `ui/src/App.tsx`:

Add imports:
```tsx
import { SessionTemplatesPage } from "./pages/SessionTemplatesPage";
import { SessionTemplateDetailPage } from "./pages/SessionTemplateDetailPage";
```

Add routes (alongside the other configuration routes):
```tsx
<Route path="/session-templates" element={<SessionTemplatesPage />} />
<Route path="/session-templates/:templateId" element={<SessionTemplateDetailPage />} />
```

- [ ] **Step 4: Commit**

```bash
git add ui/src/pages/SessionTemplatesPage.tsx \
       ui/src/pages/SessionTemplateDetailPage.tsx \
       ui/src/components/AppSidebar.tsx \
       ui/src/App.tsx
git commit -m "Add template management pages under Configuration > AI Assistant"
```

---

### Task 7: Update Session Creation Flow with Template Picker

Update `AssistantPage.tsx` to show a template picker when creating a new session, and update
`AssistantSessionPage.tsx` and `AssistantChatPanel.tsx` to conditionally show the sidebar/Apply button
and use the template's welcome message.

**Files:**
- Modify: `ui/src/pages/AssistantPage.tsx`
- Modify: `ui/src/pages/AssistantSessionPage.tsx`
- Modify: `ui/src/components/assistant/AssistantChatPanel.tsx`

**Interfaces:**
- Consumes: `fetchAssistantTemplates()`, `createAssistantSession(templateId, name)`,
  `fetchAssistantTemplate(templateId)`, `AssistantSessionInfo.templateId` — all from Task 5
- Produces: Updated session creation flow with template picker, conditional sidebar/Apply,
  template-driven welcome message

- [ ] **Step 1: Update AssistantPage with template picker**

In `ui/src/pages/AssistantPage.tsx`:

Add imports for `fetchAssistantTemplates` and `SessionTemplate`.

The create flow changes from a single name modal to a two-step flow:

1. "New Session" opens a template picker modal showing available templates as selectable cards
2. After selecting a template, a second modal (or the same modal transitions) asks for the session
   name

Replace the existing create modal state with:

```typescript
const [templates, setTemplates] = useState<SessionTemplate[]>([]);
const [isTemplatePickerOpen, setIsTemplatePickerOpen] = useState(false);
const [selectedTemplate, setSelectedTemplate] = useState<SessionTemplate | null>(null);
const [isNameModalOpen, setIsNameModalOpen] = useState(false);
```

Load templates when the picker opens:
```typescript
const openTemplatePicker = () => {
    fetchAssistantTemplates()
        .then(setTemplates)
        .catch(console.error);
    setIsTemplatePickerOpen(true);
};
```

Handle template selection:
```typescript
const handleTemplateSelect = (template: SessionTemplate) => {
    setSelectedTemplate(template);
    setIsTemplatePickerOpen(false);
    setNewName(generateSessionName());
    setIsNameModalOpen(true);
};
```

Update `handleCreate` to pass `templateId`:
```typescript
const handleCreate = () => {
    if (!selectedTemplate) return;
    setCreating(true);
    createAssistantSession(selectedTemplate.templateId, newName || undefined)
        .then((session) => {
            setIsNameModalOpen(false);
            setNewName("");
            setSelectedTemplate(null);
            navigate(`/assistant/${session.id}`);
        })
        .catch((err) => {
            setCreateError(err.message);
        })
        .finally(() => setCreating(false));
};
```

Replace the existing create modal with two modals:

Template picker modal:
```tsx
<Modal isOpen={isTemplatePickerOpen}
    onClose={() => setIsTemplatePickerOpen(false)}
    variant="medium" aria-label="Choose template">
    <ModalHeader title="Choose a Template" />
    <ModalBody>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr",
            gap: 12 }}>
            {templates.map((t) => (
                <div key={t.templateId}
                    onClick={() => handleTemplateSelect(t)}
                    style={{
                        border: "1px solid #d2d2d2",
                        borderRadius: 8,
                        padding: 16,
                        cursor: "pointer",
                    }}
                    onMouseOver={(e) =>
                        (e.currentTarget.style.borderColor = "#0066cc")
                    }
                    onMouseOut={(e) =>
                        (e.currentTarget.style.borderColor = "#d2d2d2")
                    }>
                    <div style={{ fontWeight: 600, marginBottom: 4 }}>
                        {t.name}
                    </div>
                    <div style={{ fontSize: 13, color: "#6a6e73" }}>
                        {t.description}
                    </div>
                </div>
            ))}
        </div>
    </ModalBody>
</Modal>
```

Name modal (same as before, just using the new state):
```tsx
<Modal isOpen={isNameModalOpen}
    onClose={() => setIsNameModalOpen(false)}
    variant="small" aria-label="Name session">
    <ModalHeader title="Name Your Session" />
    <ModalBody>
        {createError && (
            <Alert variant="danger" isInline title={createError}
                style={{ marginBottom: 12 }} />
        )}
        <Form>
            <FormGroup label="Session Name" fieldId="session-name">
                <TextInput id="session-name" value={newName}
                    onChange={(_e, v) => setNewName(v)}
                    onKeyDown={(e) => {
                        if (e.key === "Enter") handleCreate();
                    }} />
            </FormGroup>
        </Form>
    </ModalBody>
    <ModalFooter>
        <Button variant="primary" onClick={handleCreate}
            isDisabled={creating} isLoading={creating}>
            Create Session
        </Button>
        <Button variant="link"
            onClick={() => setIsNameModalOpen(false)}>
            Cancel
        </Button>
    </ModalFooter>
</Modal>
```

Update all "New Session" button `onClick` handlers to call `openTemplatePicker()` instead of
`setIsCreateOpen(true)`.

- [ ] **Step 2: Update AssistantSessionPage for conditional sidebar/Apply**

In `ui/src/pages/AssistantSessionPage.tsx`:

The page already has `session.templateId` available (from the updated `AssistantSessionInfo` type).

Add a computed flag:
```typescript
const isConfigAssistant = session?.templateId === "axiom-config-assistant";
```

Conditionally render the Apply button — only show it when `isConfigAssistant`:
```tsx
{isConfigAssistant && (
    <Button variant="primary" onClick={handleApply}
        isLoading={applying} isDisabled={applying}
        style={{ marginRight: 8 }}>
        Apply All
    </Button>
)}
```

Conditionally render the two-panel vs. full-width layout. Replace the split panels div with:
```tsx
<div style={{
    display: "flex",
    flex: "1 1 0",
    minHeight: 0,
    overflow: "hidden",
}}>
    <div style={{
        flex: isConfigAssistant ? "7 1 0" : "1 1 0",
        borderRight: isConfigAssistant ? "1px solid #d2d2d2" : "none",
        display: "flex",
        flexDirection: "column",
        minWidth: 0,
        minHeight: 0,
    }}>
        <AssistantChatPanel
            sessionId={sessionId}
            templateId={session.templateId}
            onItemsChanged={isConfigAssistant ? handleItemsChanged : undefined}
        />
    </div>

    {isConfigAssistant && (
        <div style={{
            flex: "3 1 0",
            overflowY: "auto",
            minWidth: 0,
            minHeight: 0,
        }}>
            <AssistantGeneratedItems
                sessionId={sessionId}
                refreshTrigger={itemsRefresh}
            />
        </div>
    )}
</div>
```

Also guard the `applyError` alert, the apply success modal, and the items-related state variables
to only be relevant when `isConfigAssistant`. The state variables can remain (they just won't be
used), but the Apply modals should be wrapped in `{isConfigAssistant && ...}`.

- [ ] **Step 3: Update AssistantChatPanel for template-driven welcome message**

In `ui/src/components/assistant/AssistantChatPanel.tsx`:

Add a `templateId` prop:
```typescript
interface AssistantChatPanelProps {
    sessionId: string;
    templateId: string;
    onItemsChanged?: () => void;
}
```

Replace the hardcoded welcome message with a dynamic one loaded from the template.

Change the initial `messages` state to start empty:
```typescript
const [messages, setMessages] = useState<ChatMessage[]>([]);
```

Add a `useEffect` that fetches the template and sets the welcome message:
```typescript
useEffect(() => {
    fetchAssistantTemplate(templateId).then((template) => {
        if (template.welcomeMessage) {
            setMessages([{
                id: String(++messageIdCounter),
                type: "assistant" as const,
                content: template.welcomeMessage,
            }]);
        }
    }).catch(console.error);
}, [templateId]);
```

Add the import for `fetchAssistantTemplate`.

- [ ] **Step 4: Commit**

```bash
git add ui/src/pages/AssistantPage.tsx \
       ui/src/pages/AssistantSessionPage.tsx \
       ui/src/components/assistant/AssistantChatPanel.tsx
git commit -m "Add template picker to session creation and conditional sidebar"
```

---

### Task 8: Integration Verification

Verify that all the pieces work together end-to-end.

**Files:**
- No new files. This task is verification only.

**Interfaces:**
- Consumes: All outputs from Tasks 1-7

- [ ] **Step 1: Verify the backend compiles**

The user builds and verifies compilation.

- [ ] **Step 2: Verify built-in template loads**

Start the application and check the logs for:
```
Loaded built-in template: axiom-config-assistant
```

- [ ] **Step 3: Verify template CRUD API**

Test the template API endpoints:
```bash
# List templates — should include the built-in
curl -s http://localhost:9090/api/v1/assistant/templates | jq .

# Get built-in template
curl -s http://localhost:9090/api/v1/assistant/templates/axiom-config-assistant | jq .

# Create a custom template
curl -s -X POST http://localhost:9090/api/v1/assistant/templates \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Template","description":"A test","systemPrompt":"# Test"}' | jq .

# Verify built-in cannot be deleted
curl -s -X DELETE http://localhost:9090/api/v1/assistant/templates/axiom-config-assistant
# Should return 403
```

- [ ] **Step 4: Verify session creation with template**

```bash
# Create session from built-in template
curl -s -X POST http://localhost:9090/api/v1/assistant/sessions \
  -H "Content-Type: application/json" \
  -d '{"name":"test","templateId":"axiom-config-assistant"}' | jq .
# Response should include "templateId": "axiom-config-assistant"

# Create session without templateId should fail
curl -s -X POST http://localhost:9090/api/v1/assistant/sessions \
  -H "Content-Type: application/json" \
  -d '{"name":"test"}' | jq .
# Should return 400
```

- [ ] **Step 5: Verify frontend flows**

1. Navigate to Configuration > AI Assistant — verify template list shows the built-in template
2. Click the built-in template — verify it opens read-only with all fields populated
3. Click Clone — verify a copy is created and the detail page opens in edit mode
4. Navigate to the Assistant page — click New Session
5. Verify the template picker modal appears
6. Select a template — verify the name modal appears
7. Create the session — verify it opens with the correct welcome message
8. For a Config Assistant session — verify the sidebar and Apply button appear
9. For a custom template session — verify full-width chat with no sidebar

- [ ] **Step 6: Commit any fixes**

```bash
git add -A
git commit -m "Fix integration issues from end-to-end verification"
```
