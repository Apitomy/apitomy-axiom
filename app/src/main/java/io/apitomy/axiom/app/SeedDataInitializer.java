package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActionTypeEntity;

import java.time.Instant;
import io.apitomy.axiom.core.entities.ActorEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.ManagerConfigEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.manager.ManagerPromptBuilder;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Seeds built-in action types on application startup if they don't already exist.
 */
@ApplicationScoped
public class SeedDataInitializer {

    private static final Logger LOG = Logger.getLogger(SeedDataInitializer.class);

    @Inject
    EncryptionService encryptionService;

    /**
     * Called on application startup to seed built-in action types.
     *
     * @param event the Quarkus startup event
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (ActionTypeEntity.count() > 0) {
            LOG.info("Action types already exist, skipping seed data");
            return;
        }

        LOG.info("Seeding built-in action types");

        seedActionType("auto-tag",
                "Determine and apply appropriate labels/tags to a GitHub issue. Use this "
                        + "when a new issue is created and needs categorization (e.g. bug, "
                        + "feature, documentation, question, good-first-issue).",
                "actor", false, true,
                "@Read-Only Tools,"
                        + "mcp__axiom-tools__list_github_labels,"
                        + "mcp__axiom-tools__apply_github_labels",
                """
                You are tagging a GitHub issue with appropriate labels.

                ## Instructions
                1. Read the issue title and body
                2. Use the list_github_labels tool to discover which labels are \
                   available in the repository
                3. Choose the most appropriate labels from the available list based \
                   on the issue content
                4. Use the apply_github_labels tool to apply the chosen labels to \
                   the issue
                5. If no suitable labels exist in the repository, skip labeling and \
                   report what labels you would have applied

                ## Issue
                {{issueRef}} in {{repository}}

                ## Context from Manager
                {{managerInput}}
                """);

        seedActionType("close-project",
                "Mark the project as completed. Use this script action when an "
                        + "issue-closed event is received, indicating the issue has "
                        + "been resolved and the project should be marked as done.",
                "script", true, false, null, null,
                """
                #!/bin/bash
                curl -s -X POST "{{apiBaseUrl}}/projects/{{projectId}}/close"
                """);

        LOG.infof("Seeded %d built-in action types", ActionTypeEntity.count());

        seedTools();
        seedToolsets();
        ensureAxiomSdkToolset();
        seedActors();
        seedManagerConfig();
        seedEventSource();
        seedReportDefinitions();
        seedSecrets();
    }

    private void seedTools() {
        if (ToolDefinitionEntity.count() > 0) {
            LOG.info("Tools already exist, skipping tool seed data");
            return;
        }

        // List GitHub Labels tool
        ToolDefinitionEntity listLabels = new ToolDefinitionEntity();
        listLabels.name = "list_github_labels";
        listLabels.description = "List all available labels in a GitHub repository with their "
                + "names and descriptions, sorted by name. Use this to discover which labels "
                + "exist before applying them to an issue.";

        listLabels.parameters = "[{\"name\":\"repo\",\"type\":\"string\",\"description\":\"Repository in owner/name format\",\"required\":true}]";
        listLabels.scriptTemplate = "gh label list --repo {{repo}} --sort name --json name,description";
        listLabels.persist();

        // Apply GitHub Labels tool
        ToolDefinitionEntity addLabels = new ToolDefinitionEntity();
        addLabels.name = "apply_github_labels";
        addLabels.description = "Apply one or more labels to a GitHub issue. Only use labels "
                + "that exist in the repository — use list_github_labels first to check.";

        addLabels.parameters = "[{\"name\":\"repo\",\"type\":\"string\",\"description\":\"Repository in owner/name format\",\"required\":true},"
                + "{\"name\":\"issue_number\",\"type\":\"number\",\"description\":\"Issue number\",\"required\":true},"
                + "{\"name\":\"labels\",\"type\":\"string\",\"description\":\"Comma-separated label names to apply\",\"required\":true}]";
        addLabels.scriptTemplate = "gh issue edit {{issue_number}} --repo {{repo}} --add-label \"{{labels}}\"";
        addLabels.persist();

        // List GitHub Issues tool (for reports)
        ToolDefinitionEntity listIssues = new ToolDefinitionEntity();
        listIssues.name = "list_github_issues";
        listIssues.description = "List GitHub issues with filters for state, labels, and date range. "
                + "Returns JSON with issue number, title, author, labels, and dates.";

        listIssues.parameters = "[{\"name\":\"repo\",\"type\":\"string\",\"description\":\"Repository in owner/name format\",\"required\":true},"
                + "{\"name\":\"state\",\"type\":\"string\",\"description\":\"Issue state: open, closed, or all\",\"required\":true},"
                + "{\"name\":\"limit\",\"type\":\"number\",\"description\":\"Maximum number of issues to return\",\"required\":false}]";
        listIssues.scriptTemplate = "gh issue list --repo {{repo}} --state {{state}} --limit {{limit}} "
                + "--json number,title,author,labels,createdAt,updatedAt,state";
        listIssues.persist();

        // List GitHub PRs tool (for reports)
        ToolDefinitionEntity listPrs = new ToolDefinitionEntity();
        listPrs.name = "list_github_prs";
        listPrs.description = "List GitHub pull requests with filters for state. "
                + "Returns JSON with PR number, title, author, review status, and dates.";

        listPrs.parameters = "[{\"name\":\"repo\",\"type\":\"string\",\"description\":\"Repository in owner/name format\",\"required\":true},"
                + "{\"name\":\"state\",\"type\":\"string\",\"description\":\"PR state: open, closed, merged, or all\",\"required\":true},"
                + "{\"name\":\"limit\",\"type\":\"number\",\"description\":\"Maximum number of PRs to return\",\"required\":false}]";
        listPrs.scriptTemplate = "gh pr list --repo {{repo}} --state {{state}} --limit {{limit}} "
                + "--json number,title,author,createdAt,updatedAt,state,reviewDecision,isDraft";
        listPrs.persist();

        LOG.infof("Seeded %d built-in tools", ToolDefinitionEntity.count());
    }

    private void seedToolsets() {
        if (ToolsetEntity.count() > 0) {
            LOG.info("Toolsets already exist, skipping toolset seed data");
            return;
        }

        seedToolset("Read-Only Tools",
                "Read-only file and git tools for analysis tasks",
                String.join(",",
                        "Read", "Glob", "Grep",
                        "Bash(ls *)", "Bash(cat *)", "Bash(head *)", "Bash(tail *)",
                        "Bash(find *)", "Bash(wc *)", "Bash(file *)",
                        "Bash(git log *)", "Bash(git diff *)", "Bash(git show *)",
                        "Bash(git status *)", "Bash(git branch *)"));

        seedToolset("Write Tools",
                "Full read/write tools plus git tools for implementation tasks",
                String.join(",",
                        "@Read-Only Tools",
                        "Edit", "Write",
                        "Bash(git add *)", "Bash(git commit *)", "Bash(git checkout *)",
                        "Bash(git switch *)", "Bash(git push *)", "Bash(git merge *)",
                        "Bash(mkdir *)", "Bash(cp *)", "Bash(mv *)"));

        LOG.infof("Seeded %d toolsets", ToolsetEntity.count());
    }

    /**
     * Ensures the "Axiom SDK" toolset exists. Called on every startup
     * (not just initial seed) so that existing databases get it on upgrade.
     */
    private void ensureAxiomSdkToolset() {
        if (ToolsetEntity.count("name", "Axiom SDK") > 0) {
            return;
        }
        seedToolset("Axiom SDK",
                "Built-in Axiom SDK tools for programmatic interaction with Axiom from AI agents",
                String.join(",",
                        "mcp__axiom-sdk__axiom_fire_event",
                        "mcp__axiom-sdk__axiom_list_projects",
                        "mcp__axiom-sdk__axiom_get_project",
                        "mcp__axiom-sdk__axiom_create_task",
                        "mcp__axiom-sdk__axiom_get_task_status",
                        "mcp__axiom-sdk__axiom_add_thread_entry",
                        "mcp__axiom-sdk__axiom_close_project",
                        "mcp__axiom-sdk__axiom_reopen_project",
                        "mcp__axiom-sdk__axiom_add_project_label",
                        "mcp__axiom-sdk__axiom_remove_project_label",
                        "mcp__axiom-sdk__axiom_list_tools",
                        "mcp__axiom-sdk__axiom_list_report_definitions"));
        LOG.info("Created 'Axiom SDK' toolset");
    }

    private void seedToolset(String name, String description, String tools) {
        ToolsetEntity entity = new ToolsetEntity();
        entity.name = name;
        entity.description = description;
        entity.tools = tools;
        entity.persist();
    }

    private void seedActors() {
        if (ActorEntity.count() > 0) {
            LOG.info("Actors already exist, skipping actor seed data");
            return;
        }

        ActorEntity actor = new ActorEntity();
        actor.name = "Blinky";
        actor.description = "AI agent powered by Claude Code CLI";
        actor.type = "ai-agent";
        actor.capabilities = "auto-tag";
        actor.persist();
        LOG.infof("Seeded actor: %s (%s)", actor.name, actor.type);

        actor = new ActorEntity();
        actor.name = "Clyde";
        actor.description = "AI agent powered by Claude Code CLI";
        actor.type = "ai-agent";
        actor.capabilities = "auto-tag";
        actor.persist();
        LOG.infof("Seeded actor: %s (%s)", actor.name, actor.type);
    }

    private void seedManagerConfig() {
        if (ManagerConfigEntity.count() > 0) {
            LOG.info("Manager config already exists, skipping seed");
            return;
        }

        ManagerConfigEntity config = new ManagerConfigEntity();
        config.systemPrompt = ManagerPromptBuilder.DEFAULT_SYSTEM_PROMPT;
        config.promptTemplate = ManagerPromptBuilder.DEFAULT_PROMPT_TEMPLATE;
        config.persist();

        LOG.info("Seeded default manager configuration");
    }

    private void seedEventSource() {
        if (EventSourceEntity.count() > 0) {
            LOG.info("Event sources already exist, skipping event source seed data");
            return;
        }

        EventSourceEntity source = new EventSourceEntity();
        source.name = "EricWittmann/cb-test-project";
        source.sourceType = "github";
        source.enabled = true;
        source.pollInterval = 30;
        source.configuration = "{\"owner\":\"EricWittmann\",\"name\":\"cb-test-project\",\"url\":\"https://github.com/EricWittmann/cb-test-project\"}";
        source.persist();

        LOG.infof("Seeded test event source: %s (polling every %ds)",
                source.name, source.pollInterval);
    }

    private void seedSecrets() {
        if (SecretEntity.count() > 0) {
            LOG.info("Secrets already exist, skipping secret seed data");
            return;
        }

        // Auto-import GitHub token from environment if available
        String ghToken = System.getenv("GH_TOKEN");
        if (ghToken == null || ghToken.isBlank()) {
            ghToken = System.getenv("GITHUB_TOKEN");
        }
        if (ghToken == null || ghToken.isBlank()) {
            ghToken = System.getenv("AXIOM_GITHUB_TOKEN");
        }

        if (ghToken != null && !ghToken.isBlank()) {
            SecretEntity secret = new SecretEntity();
            secret.name = "GH_TOKEN";
            secret.description = "GitHub personal access token for gh CLI authentication";
            secret.encryptedValue = encryptionService.encrypt(ghToken);
            secret.persist();

            SecretEntity secret2 = new SecretEntity();
            secret2.name = "GITHUB_TOKEN";
            secret2.description = "GitHub token (alias for GH_TOKEN)";
            secret2.encryptedValue = encryptionService.encrypt(ghToken);
            secret2.persist();

            LOG.info("Auto-imported GitHub token from environment into secrets store");
        } else {
            LOG.info("No GitHub token found in environment — add via Configuration > Secrets");
        }
    }

    private void seedReportDefinitions() {
        if (ReportDefinitionEntity.count() > 0) {
            LOG.info("Report definitions already exist, skipping seed data");
            return;
        }

        Instant now = Instant.now();

        String reportTools = "@Read-Only Tools,"
                + "Bash(gh issue *),Bash(gh pr *),Bash(gh api *),Bash(gh repo *),Bash(date *),"
                + "mcp__axiom-tools__list_github_issues,"
                + "mcp__axiom-tools__list_github_prs";

        ReportDefinitionEntity daily = new ReportDefinitionEntity();
        daily.name = "Daily GitHub Activity";
        daily.description = "A daily summary of all issue and PR activity across monitored repositories.";
        daily.schedule = "daily";
        daily.scheduleTime = "08:00";
        daily.timeWindow = "last-24h";
        daily.enabled = false;
        daily.allowedTools = reportTools;
        daily.promptTemplate = """
                Generate a daily activity report for the following repositories: {{repositories}}

                **Time period:** {{timeWindow}}

                Use the list_github_issues and list_github_prs tools to gather data.

                Include the following sections:
                1. **Summary** — key metrics (new issues, closed issues, merged PRs, open PRs)
                2. **New Issues** — table with issue #, title, author, labels
                3. **Closed Issues** — table with issue #, title, who closed it
                4. **Pull Requests Merged** — table with PR #, title, author
                5. **Pull Requests Awaiting Review** — table with PR #, title, author, age

                Format all issue/PR references as clickable markdown links to GitHub.
                Start the report with a level-1 heading including the date.
                """;
        daily.createdOn = now;
        daily.updatedOn = now;
        daily.persist();

        LOG.infof("Seeded %d report definitions (all disabled by default)",
                ReportDefinitionEntity.count());
    }

    private void seedActionType(String name, String description, String executionMode,
                                boolean userTriggerable, boolean emitsEvent, String allowedTools,
                                String promptTemplate) {
        seedActionType(name, description, executionMode, userTriggerable, emitsEvent,
                allowedTools, promptTemplate, null);
    }

    private void seedActionType(String name, String description, String executionMode,
                                boolean userTriggerable, boolean emitsEvent, String allowedTools,
                                String promptTemplate, String scriptTemplate) {
        ActionTypeEntity entity = new ActionTypeEntity();
        entity.name = name;
        entity.description = description;
        entity.executionMode = executionMode;
        entity.userTriggerable = userTriggerable;
        entity.managerTriggerable = true;
        entity.emitsEvent = emitsEvent;
        entity.allowedTools = allowedTools;
        entity.promptTemplate = promptTemplate;
        entity.scriptTemplate = scriptTemplate;
        entity.persist();
    }
}
