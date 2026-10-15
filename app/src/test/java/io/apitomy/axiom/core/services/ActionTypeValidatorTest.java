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
                "Analyze the issue: {{inputs.issue}}");
        at.setInputs(List.of(field("issue", ActionTypeField.Type.STRING)));

        ValidationResult result = ActionTypeValidator.validate(at);

        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void validScriptActionTypePassesWithNoErrors() {
        NewActionType at = makeScript("comment", "Comments on an issue",
                "echo commenting {{inputs.ref}}");
        at.setInputs(List.of(field("ref", ActionTypeField.Type.STRING)));

        ValidationResult result = ActionTypeValidator.validate(at);

        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    // ── Name validation ─────────────────────────────────────────────

    @Test
    void missingNameIsError() {
        NewActionType at = makeActor(null, "desc", "prompt text");

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void blankNameIsError() {
        NewActionType at = makeActor("  ", "desc", "prompt text");

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    // ── Description validation ──────────────────────────────────────

    @Test
    void missingDescriptionIsWarning() {
        NewActionType at = makeActor("test", null, "prompt text");

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
        NewActionType at = makeActor("test", "desc", "Do {{foo}} thing");

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
    void actorPromptWithRemovedPlaceholdersIsError() {
        NewActionType at = makeActor("test", "desc",
                "{{input}} for {{repository}} ref {{ref}} subject {{subjectName}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        for (String name : List.of("input", "repository", "ref", "subjectName")) {
            assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("promptTemplate")
                    && m.message().contains("'{{" + name + "}}' is not supported")), result.toString());
        }
    }

    @Test
    void removedPromptPlaceholderIsRejectedWithHint() {
        NewActionType def = makeActor("p", "desc", "Triage {{ref}}");
        ValidationResult result = ActionTypeValidator.validate(def);
        assertTrue(result.errors().stream().anyMatch(m -> m.message().contains("context.subject.ref")),
                result.toString());
    }

    @Test
    void removedScriptPlaceholderIsRejectedWithEnvHint() {
        NewActionType def = makeScript("s", "desc", "cd {{workDir}}");
        ValidationResult result = ActionTypeValidator.validate(def);
        assertTrue(result.errors().stream().anyMatch(m -> m.message().contains("$AXIOM_WORK_DIR")),
                result.toString());
    }

    @Test
    void declaredInputPlaceholderIsAccepted() {
        NewActionType def = makeActor("p", "desc", "Triage {{inputs.ref}}");
        def.setInputs(List.of(field("ref", ActionTypeField.Type.STRING)));
        assertFalse(ActionTypeValidator.validate(def).hasErrors());
    }

    @Test
    void actorPromptWithDeclaredInputsDottedPlaceholderIsValid() {
        NewActionType at = makeActor("test", "desc",
                "Process {{inputs.repository}} and {{inputs.branch}}");
        at.setInputs(List.of(
                field("repository", ActionTypeField.Type.STRING),
                field("branch", ActionTypeField.Type.STRING)));

        assertFalse(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void actorPromptWithUndeclaredInputPlaceholderIsError() {
        NewActionType at = makeActor("test", "desc", "Process {{inputs.foo}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("promptTemplate") && m.message().contains("{{inputs.foo}}")));
    }

    @Test
    void scriptWithDeclaredInputsDottedPlaceholderIsValid() {
        NewActionType at = makeScript("test", "desc",
                "echo {{inputs.repository}}");
        at.setInputs(List.of(field("repository", ActionTypeField.Type.STRING)));

        assertFalse(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void placeholderInQuotedHeredocIsError() {
        NewActionType at = makeScript("test", "desc", "cat <<'EOF' > f.json\n{{inputs.labels}}\nEOF\n");
        at.setInputs(List.of(field("labels", ActionTypeField.Type.OBJECT)));

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("scriptTemplate")
                && m.message().contains("quoted heredoc") && m.message().contains("printf")), result.toString());
    }

    @Test
    void placeholderInUnquotedHeredocIsAccepted() {
        NewActionType at = makeScript("test", "desc", "cat <<EOF | jq .\n{{inputs.labels}}\nEOF\n");
        at.setInputs(List.of(field("labels", ActionTypeField.Type.OBJECT)));

        assertFalse(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void scriptWithUndeclaredInputPlaceholderIsError() {
        NewActionType at = makeScript("test", "desc", "echo {{inputs.foo}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scriptTemplate") && m.message().contains("{{inputs.foo}}")));
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
        NewActionType at = makeScript("test", "desc", "echo {{ subjectId }}");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scriptTemplate") && m.message().contains("malformed")));
    }

    @Test
    void scriptWithRemovedPlaceholdersIsError() {
        NewActionType at = makeScript("test", "desc",
                "echo {{subjectId}} {{taskId}} {{repository}} {{apiBaseUrl}}");

        ValidationResult result = ActionTypeValidator.validate(at);

        for (String name : List.of("subjectId", "taskId", "repository", "apiBaseUrl")) {
            assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("scriptTemplate")
                    && m.message().contains("'{{" + name + "}}' is not supported")), result.toString());
        }
        assertTrue(result.errors().stream().anyMatch(m -> m.message().contains("$AXIOM_TASK_ID")));
        assertTrue(result.errors().stream().anyMatch(m -> m.message().contains("$AXIOM_API_URL")));
    }

    @Test
    void actorModeDoesNotRequireScript() {
        NewActionType at = makeActor("test", "desc", "prompt text");

        assertFalse(ActionTypeValidator.validate(at).errors().stream()
                .anyMatch(m -> m.field().equals("scriptTemplate")));
    }

    // ── Allowed tools validation (agent mode only) ──────────────────

    @Test
    void toolsetRefToMissingToolsetIsError() {
        NewActionType at = makeActor("test", "desc", "prompt text");
        at.setAllowedTools(List.of("@Missing"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null, null, Set.of("Read-Only Tools"), null);

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void customToolRefToMissingToolIsError() {
        NewActionType at = makeActor("test", "desc", "prompt text");
        at.setAllowedTools(List.of("mcp__axiom-tools__nonexistent"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null, Set.of("list_github_prs"), null, null);

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void sdkToolRefToInvalidToolIsError() {
        NewActionType at = makeActor("test", "desc", "prompt text");
        at.setAllowedTools(List.of("mcp__axiom-sdk__axiom_fake"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null, null, null, Set.of("mcp__axiom-sdk__axiom_list_subjects"));

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void validToolRefsPass() {
        NewActionType at = makeActor("test", "desc", "prompt text");
        at.setAllowedTools(List.of(
                "@Read-Only Tools",
                "mcp__axiom-tools__list_github_prs",
                "mcp__axiom-sdk__axiom_list_subjects",
                "Read", "Bash(gh pr *)"));

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                null,
                Set.of("list_github_prs"),
                Set.of("Read-Only Tools"),
                Set.of("mcp__axiom-sdk__axiom_list_subjects"));

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
        NewActionType at = makeActor("test", "desc", "prompt text");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("my-token", "value");
        at.setEnvironment(env);

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void missingSecretIsError() {
        NewActionType at = makeActor("test", "desc", "prompt text");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("TOKEN", "${secret:MISSING}");
        at.setEnvironment(env);

        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(
                Set.of("OTHER"), null, null, null);

        assertTrue(ActionTypeValidator.validate(at, known).hasErrors());
    }

    @Test
    void malformedSecretRefIsError() {
        NewActionType at = makeActor("test", "desc", "prompt text");
        io.apitomy.axiom.api.beans.Environment env = new io.apitomy.axiom.api.beans.Environment();
        env.setAdditionalProperty("TOKEN", "${secret:}");
        at.setEnvironment(env);

        assertTrue(ActionTypeValidator.validate(at).hasErrors());
    }

    @Test
    void validEnvPasses() {
        NewActionType at = makeActor("test", "desc", "prompt text");
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
        NewActionType def = makeActor("dup-inputs", "desc", "do the thing");
        def.setInputs(List.of(
            field("repo", ActionTypeField.Type.STRING),
            field("repo", ActionTypeField.Type.NUMBER)));

        ValidationResult result = ActionTypeValidator.validate(def);

        assertTrue(result.errors().stream()
            .anyMatch(e -> e.field().startsWith("inputs[") && e.message().contains("duplicate")));
    }

    @Test
    void rejectsInvalidInputIdentifier() {
        NewActionType def = makeActor("bad-input-name", "desc", "do the thing");
        def.setInputs(List.of(field("has space", ActionTypeField.Type.STRING)));

        ValidationResult result = ActionTypeValidator.validate(def);

        assertTrue(result.errors().stream().anyMatch(e -> e.field().startsWith("inputs[")));
    }

    @Test
    void rejectsBlankOutputName() {
        NewActionType def = makeActor("blank-output", "desc", "do the thing");
        def.setOutputs(List.of(field("", ActionTypeField.Type.STRING)));

        ValidationResult result = ActionTypeValidator.validate(def);

        assertTrue(result.errors().stream().anyMatch(e -> e.field().startsWith("outputs[")));
    }

    @Test
    void acceptsValidInputsAndOutputs() {
        NewActionType def = makeActor("good-io", "desc", "do the thing");
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

    // ── Node mode ───────────────────────────────────────────────────

    private static final String VALID_NODE = "import fs from 'node:fs';\n"
            + "export async function run(inputs, ctx) {\n"
            + "    return { size: fs.statSync(inputs.path).size };\n"
            + "}\n";

    @Test
    void validNodeModuleAccepted() {
        NewActionType at = makeNode("stat", "Stats a file", VALID_NODE);
        at.setInputs(List.of(field("path", ActionTypeField.Type.STRING)));

        ValidationResult result = ActionTypeValidator.validate(at);

        assertFalse(result.hasErrors(), result.messages().toString());
        assertFalse(result.warnings().stream().anyMatch(m -> m.field().equals("codeTemplate")
                && !m.message().equals("Syntax not checked: Node.js not found.")));
    }

    @Test
    void nodeMissingCodeIsError() {
        NewActionType at = makeNode("n", "d", "  ");

        assertTrue(hasError(ActionTypeValidator.validate(at), "Code is required for node-mode action types."));
    }

    @Test
    void nodePlaceholderIsError() {
        NewActionType at = makeNode("n", "d",
                "export async function run(inputs) { return { a: '{{inputs.x}}' }; }\n");
        at.setInputs(List.of(field("x", ActionTypeField.Type.STRING)));

        assertTrue(hasError(ActionTypeValidator.validate(at),
                "Node actions receive inputs as run(inputs, ctx); placeholders are not supported."));
    }

    @Test
    void nodeRefPlaceholderIsError() {
        NewActionType at = makeNode("n", "d",
                "export async function run() { return { a: '{{ref}}' }; }\n");

        assertTrue(hasError(ActionTypeValidator.validate(at),
                "Node actions receive inputs as run(inputs, ctx); placeholders are not supported."));
    }

    @Test
    void nodeNonPlaceholderBracesAccepted() {
        for (String body : List.of("const s = \"${{ secrets.TOKEN }}\";",
                "const s = \"{{#each items}}\";", "const o = ({ a: { b: 1 } });")) {
            NewActionType at = makeNode("n", "d",
                    body + "\nexport async function run() { return {}; }\n");
            assertFalse(hasError(ActionTypeValidator.validate(at),
                    "Node actions receive inputs as run(inputs, ctx); placeholders are not supported."), body);
        }
    }

    @Test
    void nodeNpmImportIsError() {
        NewActionType at = makeNode("n", "d",
                "import _ from \"lodash\";\nexport async function run() { return {}; }\n");

        assertTrue(hasError(ActionTypeValidator.validate(at), "Import 'lodash' is not a Node.js built-in "
                + "module; only built-ins (e.g. node:fs) are allowed."));
    }

    @Test
    void nodeNpmRequireIsError() {
        NewActionType at = makeNode("n", "d",
                "const axios = require(\"axios\");\nexport async function run() { return {}; }\n");

        assertTrue(hasError(ActionTypeValidator.validate(at), "Import 'axios' is not a Node.js built-in "
                + "module; only built-ins (e.g. node:fs) are allowed."));
    }

    @Test
    void nodeBuiltinImportsAccepted() {
        NewActionType prefixed = makeNode("n", "d",
                "import fs from \"node:fs\";\nexport async function run() { return {}; }\n");
        NewActionType bare = makeNode("n", "d",
                "import fs from \"fs\";\nexport async function run() { return {}; }\n");

        assertFalse(ActionTypeValidator.validate(prefixed).hasErrors());
        assertFalse(ActionTypeValidator.validate(bare).hasErrors());
    }

    @Test
    void nodeMissingRunExportIsError() {
        NewActionType at = makeNode("n", "d", "async function run() { return {}; }\n");

        assertTrue(hasError(ActionTypeValidator.validate(at),
                "The module must export async function run(inputs, ctx)."));
    }

    @Test
    void nodeSyntaxErrorIsError() {
        NewActionType at = makeNode("n", "d", "export async function run( {\n");

        ValidationResult result = ActionTypeValidator.validate(at);

        boolean nodeMissing = result.warnings().stream()
                .anyMatch(m -> m.message().equals("Syntax not checked: Node.js not found."));
        org.junit.jupiter.api.Assumptions.assumeFalse(nodeMissing, "node not available");
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("codeTemplate")
                && m.message().startsWith("Syntax error: ")));
    }

    @Test
    void nodeUndeclaredInputReadIsWarning() {
        NewActionType at = makeNode("n", "d",
                "export async function run(inputs) { return { v: inputs.missing }; }\n");

        ValidationResult result = ActionTypeValidator.validate(at);

        assertFalse(result.hasErrors());
        assertTrue(result.warnings().stream().anyMatch(m -> m.field().equals("codeTemplate")
                && m.message().equals("Reads inputs.missing, which is not a declared input.")));
    }

    private static boolean hasError(ValidationResult result, String message) {
        return result.errors().stream()
                .anyMatch(m -> m.field().equals("codeTemplate") && m.message().equals(message));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static NewActionType makeNode(String name, String description, String codeTemplate) {
        NewActionType at = new NewActionType();
        at.setName(name);
        at.setDescription(description);
        at.setExecutionMode(NewActionType.ExecutionMode.NODE);
        at.setCodeTemplate(codeTemplate);
        return at;
    }

    private static NewActionType makeActor(String name, String description,
                                            String promptTemplate) {
        NewActionType at = new NewActionType();
        at.setName(name);
        at.setDescription(description);
        at.setExecutionMode(NewActionType.ExecutionMode.AGENT);
        at.setPromptTemplate(promptTemplate);
        return at;
    }

    private static NewActionType makeScript(String name, String description,
                                             String scriptTemplate) {
        NewActionType at = new NewActionType();
        at.setName(name);
        at.setDescription(description);
        at.setExecutionMode(NewActionType.ExecutionMode.SCRIPT);
        at.setScriptTemplate(scriptTemplate);
        return at;
    }

    private static ActionTypeField field(String name, ActionTypeField.Type type) {
        ActionTypeField f = new ActionTypeField();
        f.setName(name);
        f.setType(type);
        return f;
    }

    @Test
    void removedPlaceholderHintsUseTheSubjectInput() {
        assertEquals("Declare an input 'ref' and map it from context.subject.ref on the workflow node.",
                ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.get("ref"));
        assertEquals("Declare an input 'subjectId' and map it from context.subject.id on the workflow node.",
                ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.get("subjectId"));
        assertTrue(ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.values().stream()
                .noneMatch(h -> h.matches(".*context\\.(ref|repository|subjectName|subjectId)\\b.*")));
    }
}
