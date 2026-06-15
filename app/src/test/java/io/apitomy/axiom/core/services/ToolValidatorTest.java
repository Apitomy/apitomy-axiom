package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.NewToolDefinition;
import io.apitomy.axiom.api.beans.ToolParameter;
import io.apitomy.axiom.core.services.ToolValidator.Severity;
import io.apitomy.axiom.core.services.ToolValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolValidator}.
 */
class ToolValidatorTest {

    // ── Valid tool ───────────────────────────────────────────────────

    @Test
    void validToolPassesWithNoErrors() {
        NewToolDefinition tool = makeTool("list-prs",
                "Lists pull requests",
                "gh pr list --repo {{repo}} --limit {{limit}}",
                param("repo", "string", true),
                param("limit", "number", false));

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void validToolWithFileParam() {
        NewToolDefinition tool = makeTool("post-comment",
                "Posts a comment",
                "gh issue comment {{issue}} --repo {{repo}} --body-file {{body_file}}",
                param("issue", "string", true),
                param("repo", "string", true),
                param("body", "string", true));

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.hasErrors());
    }

    // ── Name validation ─────────────────────────────────────────────

    @Test
    void missingNameIsError() {
        NewToolDefinition tool = makeTool(null, "desc", "echo hi");

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("name") && m.message().contains("required")));
    }

    @Test
    void blankNameIsError() {
        NewToolDefinition tool = makeTool("  ", "desc", "echo hi");

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(m -> m.field().equals("name")));
    }

    @Test
    void uppercaseNameIsWarning() {
        NewToolDefinition tool = makeTool("MyTool", "desc", "echo hi");

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(
                m -> m.field().equals("name") && m.message().contains("lowercase")));
    }

    @Test
    void kebabCaseNameIsValid() {
        NewToolDefinition tool = makeTool("list-github-prs", "desc", "echo hi");

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.warnings().stream().anyMatch(m -> m.field().equals("name")));
    }

    @Test
    void snakeCaseNameIsValid() {
        NewToolDefinition tool = makeTool("list_github_prs", "desc", "echo hi");

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.warnings().stream().anyMatch(m -> m.field().equals("name")));
    }

    // ── Description validation ──────────────────────────────────────

    @Test
    void missingDescriptionIsWarning() {
        NewToolDefinition tool = makeTool("my-tool", null, "echo hi");

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.hasErrors());
        assertTrue(result.warnings().stream().anyMatch(
                m -> m.field().equals("description")));
    }

    @Test
    void blankDescriptionIsWarning() {
        NewToolDefinition tool = makeTool("my-tool", "  ", "echo hi");

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.warnings().stream().anyMatch(
                m -> m.field().equals("description")));
    }

    // ── Script template validation ──────────────────────────────────

    @Test
    void missingScriptTemplateIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc", null);

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().equals("scriptTemplate") && m.message().contains("required")));
    }

    @Test
    void blankScriptTemplateIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "  ");

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
    }

    // ── Parameter validation ────────────────────────────────────────

    @Test
    void duplicateParameterNamesIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "echo {{repo}} {{repo}}",
                param("repo", "string", true),
                param("repo", "string", false));

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("Duplicate")));
    }

    @Test
    void parameterWithNoNameIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "echo hi");
        ToolParameter p = new ToolParameter();
        p.setType(ToolParameter.Type.STRING);
        tool.setParameters(List.of(p));

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().contains("parameters[0]") && m.message().contains("no name")));
    }

    @Test
    void parameterWithInvalidNameIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "echo {{123bad}}",
                param("123bad", "string", true));

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("not a valid identifier")));
    }

    @Test
    void parameterWithNoTypeIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "echo {{repo}}");
        ToolParameter p = new ToolParameter();
        p.setName("repo");
        tool.setParameters(List.of(p));

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("missing a type")));
    }

    // ── Placeholder consistency ─────────────────────────────────────

    @Test
    void undefinedPlaceholderIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc",
                "gh pr list --repo {{repo}} --limit {{limit}}",
                param("repo", "string", true));

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("limit") && m.message().contains("no parameter")));
    }

    @Test
    void unusedParameterIsWarning() {
        NewToolDefinition tool = makeTool("my-tool", "desc",
                "gh pr list --repo {{repo}}",
                param("repo", "string", true),
                param("limit", "number", false));

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(
                m -> m.message().contains("limit") && m.message().contains("never referenced")));
    }

    @Test
    void fileParamCountsAsUsed() {
        NewToolDefinition tool = makeTool("my-tool", "desc",
                "cat {{content_file}}",
                param("content", "string", true));

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.hasErrors());
        assertFalse(result.warnings().stream().anyMatch(
                m -> m.message().contains("content") && m.message().contains("never referenced")));
    }

    @Test
    void undefinedFilePlaceholderIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc",
                "cat {{payload_file}}");

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.message().contains("payload")));
    }

    @Test
    void noParamsNoPlaceholdersIsValid() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "echo hello world");

        ValidationResult result = ToolValidator.validate(tool);

        assertFalse(result.hasErrors());
    }

    // ── Labels validation ───────────────────────────────────────────

    @Test
    void blankLabelIsError() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "echo hi");
        tool.setLabels(List.of("good", "  "));

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(
                m -> m.field().contains("labels") && m.message().contains("blank")));
    }

    @Test
    void duplicateLabelsIsWarning() {
        NewToolDefinition tool = makeTool("my-tool", "desc", "echo hi");
        tool.setLabels(List.of("github", "GitHub"));

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(
                m -> m.message().contains("Duplicate label")));
    }

    // ── Result helpers ──────────────────────────────────────────────

    @Test
    void errorsFilterCorrectly() {
        NewToolDefinition tool = makeTool(null, null, null);

        ValidationResult result = ToolValidator.validate(tool);

        assertTrue(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertFalse(result.errors().isEmpty());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.errors().stream().allMatch(m -> m.severity() == Severity.ERROR));
        assertTrue(result.warnings().stream().allMatch(m -> m.severity() == Severity.WARNING));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static NewToolDefinition makeTool(String name, String description,
                                               String scriptTemplate,
                                               ToolParameter... params) {
        NewToolDefinition tool = new NewToolDefinition();
        tool.setName(name);
        tool.setDescription(description);
        tool.setScriptTemplate(scriptTemplate);
        if (params.length > 0) {
            tool.setParameters(List.of(params));
        }
        return tool;
    }

    private static ToolParameter param(String name, String type, boolean required) {
        ToolParameter p = new ToolParameter();
        p.setName(name);
        p.setType(ToolParameter.Type.fromValue(type));
        p.setRequired(required);
        return p;
    }
}
