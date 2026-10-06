package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that agent prompts are assembled only from declared inputs: the rendered template, an
 * "## Inputs" section and the output contract, with no subject data in the system prompt.
 */
@QuarkusTest
class TaskPromptAssemblyTest {

    @Inject
    TaskExecutionService taskExecutionService;

    private ActionTypeEntity actionType(String name) {
        ActionTypeEntity at = new ActionTypeEntity();
        at.name = name;
        at.description = "Triages an issue";
        at.executionMode = "agent";
        at.promptTemplate = "Triage the issue titled {{inputs.issue}}.";
        at.inputs = new java.util.ArrayList<>(List.of(
                new ActionTypeField("issue", "string", true, "The issue title"),
                new ActionTypeField("meta", "object", false, null)));
        at.outputs = new java.util.ArrayList<>(List.of(
                new ActionTypeField("label", "string", true, "Chosen label")));
        return at;
    }

    private SubjectEntity subject() {
        SubjectEntity subject = new SubjectEntity();
        subject.id = 5L;
        subject.name = "Secret Subject Name";
        subject.ref = "secret/ref#9";
        subject.body = "Secret subject body";
        return subject;
    }

    private TaskEntity task(String actionType, String input) {
        TaskEntity task = new TaskEntity();
        task.id = 11L;
        task.subjectId = 5L;
        task.actionType = actionType;
        task.workflowRunId = 77L;
        task.input = input;
        return task;
    }

    @Test
    void promptContainsTemplateInputsSectionThenOutputContract() {
        ActionTypeEntity at = actionType("prompt-assembly");
        String prompt = taskExecutionService.resolvePromptTemplate(at,
                task(at.name, "{\"issue\":\"Bug X\",\"meta\":{\"a\":1}}"));

        assertNotNull(prompt);
        assertTrue(prompt.contains("Triage the issue titled Bug X."), prompt);
        assertTrue(prompt.contains("## Inputs"), prompt);
        assertTrue(prompt.contains("**issue**"), prompt);
        assertTrue(prompt.contains("**meta**"), prompt);
        assertTrue(prompt.contains("```json"), prompt);
        int inputsAt = prompt.indexOf("## Inputs");
        int outputAt = prompt.indexOf("## Required output");
        assertTrue(outputAt > inputsAt, prompt);
    }

    @Test
    void systemPromptHasActionNameAndNoSubjectData() {
        SubjectEntity subject = subject();
        String systemPrompt = taskExecutionService.buildSystemPrompt(actionType("prompt-assembly"));

        assertTrue(systemPrompt.contains("prompt-assembly"), systemPrompt);
        assertTrue(systemPrompt.contains("Triages an issue"), systemPrompt);
        assertFalse(systemPrompt.contains(subject.name), systemPrompt);
        assertFalse(systemPrompt.contains(subject.ref), systemPrompt);
        assertFalse(systemPrompt.contains(subject.body), systemPrompt);
    }

    @Test
    void taskMissingRequiredInputFails() {
        String name = "prompt-assembly-" + UUID.randomUUID();
        long[] taskId = new long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            actionType(name).persist();

            SubjectEntity subject = new SubjectEntity();
            subject.name = "missing-input";
            subject.type = "test";
            subject.ref = "missing-input-" + name;
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();

            TaskEntity task = new TaskEntity();
            task.subjectId = subject.id;
            task.actionType = name;
            task.createdBy = "test";
            task.status = "Pending";
            task.createdOn = Instant.now();
            task.workflowRunId = -1L;
            task.input = "{\"meta\":{\"a\":1}}";
            task.persist();
            taskId[0] = task.id;
        });

        QuarkusTransaction.requiringNew().run(() ->
                taskExecutionService.executeTask(TaskEntity.findById(taskId[0])));

        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = TaskEntity.findById(taskId[0]);
            assertEquals("Failed", task.status);
            assertEquals("Missing required input(s): issue. Map them on the workflow node.", task.output);
        });
    }
}
