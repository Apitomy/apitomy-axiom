package io.apitomy.axiom.app;

import io.apitomy.axiom.core.SdkFunctionRegistry;
import io.apitomy.axiom.core.entities.ActionTypeEntity;

import java.util.List;
import io.apitomy.axiom.core.entities.AgentCapabilityEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.ManagerConfigEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.SystemConfigEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.manager.ManagerPromptBuilder;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Seeds built-in action types on application startup if they don't already exist.
 */
@ApplicationScoped
public class SeedDataInitializer {

    private static final Logger LOG = Logger.getLogger(SeedDataInitializer.class);

    @ConfigProperty(name = "axiom.agent.default-type", defaultValue = "claude-code")
    String defaultAgentType;

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

        seedActionType("close-project",
                "Mark the project as completed. Use this script action when an "
                        + "issue-closed event is received, indicating the issue has "
                        + "been resolved and the project should be marked as done.",
                "script", true, false, null, null,
                """
                #!/bin/bash
                curl -s -X POST "{{apiBaseUrl}}/projects/{{projectId}}/close"
                """, true);

        LOG.infof("Seeded %d built-in action types", ActionTypeEntity.count());

        seedToolsets();
        ensureAxiomSdkToolset();
        seedAgents();
        seedManagerConfig();
        seedSystemConfig();
        seedRetentionConfig();
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
                SdkFunctionRegistry.allToolNamesCsv());
        LOG.info("Created 'Axiom SDK' toolset");
    }

    private void seedToolset(String name, String description, String tools) {
        ToolsetEntity entity = new ToolsetEntity();
        entity.name = name;
        entity.description = description;
        entity.tools = tools;
        entity.persist();
    }

    private void seedAgents() {
        if (AgentEntity.count() > 0) {
            LOG.info("Agents already exist, skipping agent seed data");
            return;
        }

        AgentEntity agent = new AgentEntity();
        agent.name = "Blinky";
        agent.description = "AI agent powered by Claude Code CLI";
        agent.agentType = "claude-code";
        agent.enabled = true;
        agent.persist();
        AgentCapabilityEntity.setCapabilities(agent.id, List.of("*"));
        LOG.infof("Seeded agent: %s (%s)", agent.name, agent.agentType);

        agent = new AgentEntity();
        agent.name = "Clyde";
        agent.description = "AI agent powered by Claude Code CLI";
        agent.agentType = "claude-code";
        agent.enabled = true;
        agent.persist();
        AgentCapabilityEntity.setCapabilities(agent.id, List.of("*"));
        LOG.infof("Seeded agent: %s (%s)", agent.name, agent.agentType);
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

    private void seedRetentionConfig() {
        if (RetentionConfigEntity.count() > 0) {
            LOG.info("Retention config already exists, skipping seed");
            return;
        }

        RetentionConfigEntity config = new RetentionConfigEntity();
        config.closedProjectRetentionDays = 90;
        config.traceRetentionDays = 30;
        config.eventRetentionDays = 90;
        config.persist();

        LOG.info("Seeded default retention configuration");
    }

    private void seedSystemConfig() {
        if (SystemConfigEntity.count() > 0) {
            LOG.info("System config already exists, skipping seed");
            return;
        }

        SystemConfigEntity config = new SystemConfigEntity();
        config.defaultEngine = defaultAgentType;
        config.persist();

        LOG.infof("Seeded system config with default engine: %s", defaultAgentType);
    }

    private void seedActionType(String name, String description, String executionMode,
                                boolean userTriggerable, boolean emitsEvent, String allowedTools,
                                String promptTemplate) {
        seedActionType(name, description, executionMode, userTriggerable, emitsEvent,
                allowedTools, promptTemplate, null, false);
    }

    private void seedActionType(String name, String description, String executionMode,
                                boolean userTriggerable, boolean emitsEvent, String allowedTools,
                                String promptTemplate, String scriptTemplate, boolean workflowEnabled) {
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
        entity.workflowEnabled = workflowEnabled;
        entity.persist();
    }
}
