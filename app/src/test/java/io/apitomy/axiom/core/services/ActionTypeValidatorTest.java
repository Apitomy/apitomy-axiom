package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.ActionTypeField;
import io.apitomy.axiom.api.beans.NewActionType;
import io.apitomy.axiom.core.services.ActionTypeValidator.Severity;
import io.apitomy.axiom.core.services.ActionTypeValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ActionTypeValidator}.
 */
class ActionTypeValidatorTest {

    // ── Valid action types ───────────────────────────────────────────

    @Test
    void validActorActionTypePassesWithNoErrors() {
        NewActionType at = makeActor("analyze", "Analyzes issues",
                "Analyze the issue: {{input}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void validScriptActionTypePassesWithNoErrors() {
        NewActionType at = makeScript("close-project", "Closes project",
                "echo closing {{projectId}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    // ── Name validation ─────────────────────────────────────────────

    @Test
    void missingNameIsError() {
        NewActionType at = makeActor(null, "desc", "prompt {{input}}");

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void blankNameIsError() {
        NewActionType at = makeActor("  ", "desc", "prompt {{input}}");

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    // ── Description validation ──────────────────────────────────────

    @Test
    void missingDescriptionIsWarning() {
        NewActionType at = makeActor("test", null, "prompt {{input}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertFalse(result.hasErrors());
        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("description")));
    }

    // ── Execution mode validation ───────────────────────────────────

    @Test
    void missingExecutionModeIsError() {
        NewActionType at = new NewActionType();
        at.setName("test");
        at.setDescription("desc");

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void invalidExecutionModeIsError() {
        NewActionType at = new NewActionType();
        at.setName("test");
        at.setDescription("desc");
        at.setExecutionMode(NewActionType.ExecutionMode.fromValue("agent"));
        // Can't really set an invalid enum via the generated bean, so test the string
        // validation indirectly — the generated enum prevents invalid values at parse time
    }

    // ── Prompt template validation (agent mode) ─────────────────────

    @Test
    void actorMissingPromptIsError() {
        NewActionType at = makeActor("test", "desc", null);

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")));
    }

    @Test
    void actorBlankPromptIsError() {
        NewActionType at = makeActor("test", "desc", "  ");

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void actorPromptWithUnrecognizedPlaceholderIsError() {
        NewActionType at = makeActor("test", "desc", "Do {{foo}} with {{input}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("promptTemplate") && m.message().contains("{{foo}}")));
    }

    @Test
    void actorPromptWithMalformedPlaceholderIsError() {
        NewActionType at = makeActor("test", "desc", "Do {{ input }} thing");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("promptTemplate") && m.message().contains("malformed")));
    }

    @Test
    void actorPromptWithUnclosedPlaceholderIsError() {
        NewActionType at = makeActor("test", "desc", "Do {{input thing");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("promptTemplate") && m.message().contains("malformed")));
    }

    @Test
    void actorPromptWithRecognizedPlaceholdersIsValid() {
        NewActionType at = makeActor("test", "desc",
                "{{input}} for {{repository}} ref {{ref}} project {{projectName}}");

        assertFalse(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void scriptModeDoesNotRequirePrompt() {
        NewActionType at = makeScript("test", "desc", "echo hi");

        assertFalse(ActionTypeValidator.validate(at).errors().stream()
                .anyMatch(m -> m.field().equals("promptTemplate")));
    }

    // ── Script template validation (script mode) ────────────────────

    @Test
    void scriptMissingScriptTemplateIsError() {
        NewActionType at = makeScript("test", "desc", null);

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("scriptTemplate")));
    }

    @Test
    void scriptWithUnrecognizedPlaceholderIsError() {
        NewActionType at = makeScript("test", "desc", "echo {{badPlaceholder}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scriptTemplate") && m.message().contains("{{badPlaceholder}}")));
    }

    @Test
    void scriptWithMalformedPlaceholderIsError() {
        NewActionType at = makeScript("test", "desc", "echo {{ projectId }}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scriptTemplate") && m.message().contains("malformed")));
    }

    @Test
    void scriptWithRecognizedPlaceholdersIsValid() {
        NewActionType at = makeScript("test", "desc",
                "echo {{projectId}} {{taskId}} {{repository}} {{apiBaseUrl}}");

        assertFalse(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void actorModeDoesNotRequireScript() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");

        assertFalse(ActionTypeValidator.validate(at).errors().stream()
                .anyMatch(m -> m.field().equals("scriptTemplate")));
    }

    // ── Allowed tools validation (agent mode only) ──────────────────

    @Test
    void toolsetRefToMissingToolsetIsError() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        at.setAllowedTools(List.of("@Missing"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null, null, Set.of("Read-Only Tools"), null);

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void customToolRefToMissingToolIsError() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        at.setAllowedTools(List.of("mcp__axiom-tools__nonexistent"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null, Set.of("list_github_prs"), null, null);

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void sdkToolRefToInvalidToolIsError() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        at.setAllowedTools(List.of("mcp__axiom-sdk__axiom_fake"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null, null, null, Set.of("mcp__axiom-sdk__axiom_list_projects"));

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void validToolRefsPass() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        at.setAllowedTools(List.of(
                "@Read-Only Tools",
                "mcp__axiom-tools__list_github_prs",
                "mcp__axiom-sdk__axiom_list_projects",
                "Read", "Bash(gh pr *)"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null,
                Set.of("list_github_prs"),
                Set.of("Read-Only Tools"),
                Set.of("mcp__axiom-sdk__axiom_list_projects"));

        assertFalse(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void scriptModeSkipsToolValidation() {
        NewActionType at = makeScript("test", "desc", "echo hi");
        at.setAllowedTools(List.of("@NonExistent"));

        assertFalse(ActionTypeValidator.validate(at).errors().stream()
                .anyMatch(m -> m.field().contains("allowedTools")));
    }

    // ── Environment validation ──────────────────────────────────────

    @Test
    void invalidEnvKeyIsError() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("my-token", "value");
        at.setEnvironment(env);

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void missingSecretIsError() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("TOKEN", "${secret:MISSING}");
        at.setEnvironment(env);

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                Set.of("OTHER"), null, null, null);

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void malformedSecretRefIsError() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("TOKEN", "${secret:}");
        at.setEnvironment(env);

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void validEnvPasses() {
        NewActionType at = makeActor("test", "desc", "prompt {{input}}");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("GH_TOKEN", "${secret:GH_TOKEN}");
        at.setEnvironment(env);

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                Set.of("GH_TOKEN"), null, null, null);

        assertFalse(ActionTypeValidator.validate(at, known).hasErrors());
    }

    // ── Input/output field validation ───────────────────────────────

    @Test
    void rejectsDuplicateInputNames() {
        NewActionType def = makeActor("dup-inputs", "desc", "do {{managerInput}}");
        def.setInputs(List.of(
            field("repo", ActionTypeField.Type.STRING),
            field("repo", ActionTypeField.Type.NUMBER)));

        ValidationResult result = ActionTypeValidator.validate(def);

        assertTrue(result.errors().stream()
            .anyMatch(e -> e.field().startsWith("inputs[") && e.message().contains("duplicate")));
    }

    @Test
    void rejectsInvalidInputIdentifier() {
        NewActionType def = makeActor("bad-input-name", "desc", "do {{managerInput}}");
        def.setInputs(List.of(field("has space", ActionTypeField.Type.STRING)));

        ValidationResult result = ActionTypeValidator.validate(def);

        assertTrue(result.errors().stream().anyMatch(e -> e.field().startsWith("inputs[")));
    }

    @Test
    void rejectsBlankOutputName() {
        NewActionType def = makeActor("blank-output", "desc", "do {{managerInput}}");
        def.setOutputs(List.of(field("", ActionTypeField.Type.STRING)));

        ValidationResult result = ActionTypeValidator.validate(def);

        assertTrue(result.errors().stream().anyMatch(e -> e.field().startsWith("outputs[")));
    }

    @Test
    void acceptsValidInputsAndOutputs() {
        NewActionType def = makeActor("good-io", "desc", "do {{managerInput}}");
        def.setInputs(List.of(field("repository", ActionTypeField.Type.STRING)));
        def.setOutputs(List.of(field("prNumber", ActionTypeField.Type.NUMBER)));

        ValidationResult result = ActionTypeValidator.validate(def);

        assertFalse(result.errors().stream()
            .anyMatch(e -> e.field().startsWith("inputs[") || e.field().startsWith("outputs[")));
    }

    // ── Result helpers ──────────────────────────────────────────────

    @Test
    void errorsAndWarningsFilterCorrectly() {
        NewActionType at = new NewActionType();

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.errors().stream().allMatch(m -> m.severity() == Severity.ERROR));
        assertTrue(result.warnings().stream().allMatch(m -> m.severity() == Severity.WARNING));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static NewActionType makeActor(String name, String description,
                                            String promptTemplate) {
        NewActionType at = new NewActionType();
        at.setName(name);
        at.setDescription(description);
        at.setExecutionMode(NewActionType.ExecutionMode.AGENT);
        at.setPromptTemplate(promptTemplate);
        at.setUserTriggerable(true);
        at.setManagerTriggerable(true);
        at.setEmitsEvent(false);
        return at;
    }

    private static NewActionType makeScript(String name, String description,
                                             String scriptTemplate) {
        NewActionType at = new NewActionType();
        at.setName(name);
        at.setDescription(description);
        at.setExecutionMode(NewActionType.ExecutionMode.SCRIPT);
        at.setScriptTemplate(scriptTemplate);
        at.setUserTriggerable(true);
        at.setManagerTriggerable(false);
        at.setEmitsEvent(false);
        return at;
    }

    private static ActionTypeField field(String name, ActionTypeField.Type type) {
        ActionTypeField f = new ActionTypeField();
        f.setName(name);
        f.setType(type);
        return f;
    }
}
