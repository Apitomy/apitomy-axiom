package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ValidationContext;
import io.apitomy.axiom.app.validation.ValidationContextFactory;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.flow.model.NodeType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AssistantReferenceTest {

    @Inject
    AssistantReferenceGenerator generator;
    @Inject
    ImportExportService importExportService;
    @Inject
    ObjectMapper mapper;
    @Inject
    ConfigItemValidationService validationService;
    @Inject
    ValidationContextFactory contextFactory;

    @Test
    void referenceExplainsHowScriptsReturnOutputs() {
        String ref = generator.generate();
        assertTrue(ref.contains("printing one JSON object to stdout"), ref);
        assertTrue(ref.contains("Outputs written to files are ignored"), ref);
        assertTrue(ref.contains("printf '%s' {{inputs.labels}} | jq -r 'join(\",\")'"), ref);
        assertTrue(ref.contains("quoted heredoc"), ref);
    }

    @Test
    void referenceDocumentsSdkActionNodes() {
        String ref = generator.generate();
        assertTrue(ref.contains("### SDK action nodes"), ref);
        // Each function: signature (engine-filled parameters marked ?) and its description.
        assertTrue(ref.contains("- `sdk:axiom_set_workflow_run_title(runId?, title)` — Set a workflow run's title"), ref);
        assertTrue(ref.contains("- `sdk:axiom_add_subject_label(subjectId?, label)`"), ref);
        assertTrue(ref.contains("`subjectId` and `runId` are filled in from the node's own run"), ref);
        assertTrue(ref.contains("Parameters marked `?` may be left unmapped"), ref);
        assertTrue(ref.contains("`context.success`"), ref);
        assertTrue(ref.contains("A failed call fails the node"), ref);
        assertTrue(ref.contains("\"actionType\": \"sdk:axiom_set_workflow_run_title\""), ref);
        assertFalse(ref.contains("may be `sdk:<function>` for:"), "the bare name list is replaced");
    }

    @Test
    void referenceDocumentsNodeMode() {
        String ref = generator.generate();
        assertTrue(ref.contains("run(inputs, ctx)"), ref);
        assertTrue(ref.contains("ctx.exec"), ref);
        assertTrue(ref.contains("Imports are limited to Node built-in modules"), ref);
        assertTrue(generator.examples().stream()
                .anyMatch(e -> e.getKey().equals("action-types/fetch-repo-labels.json")),
                "examples must include action-types/fetch-repo-labels.json");
    }

    @Test
    void configAssistantPrefersNodeOverBash() throws Exception {
        String template = new String(getClass().getResourceAsStream(
                "/templates/assistant-templates/axiom-config-assistant.json").readAllBytes(),
                StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(template);
        String prompt = json.path("systemPrompt").asText();
        assertTrue(prompt.contains("`node` by default"), "prompt must make node the default non-AI mode");
        assertTrue(prompt.contains("`node` mode"), "prompt concepts must mention node mode");
        assertTrue(json.path("welcomeMessage").asText().contains("Node.js"), "welcome message must mention Node.js");

        String ref = generator.generate();
        for (String expected : List.of("Choosing an execution mode", "Node.js pitfalls", "`require` is not defined",
                "no shell", "`ctx.log`", "process.env", "node --check", "error.result")) {
            assertTrue(ref.contains(expected), "reference must mention: " + expected);
        }
        assertTrue(generator.examples().stream()
                .anyMatch(e -> e.getKey().equals("action-types/add-issue-comment.json")),
                "examples must include action-types/add-issue-comment.json");
    }

    /**
     * The Flow 2.6/2.7 node options are documented, shown in the example workflow and explained in the prompt.
     */
    @Test
    void referenceCoversFlowReceiveEventAndHumanTaskOptions() throws Exception {
        String ref = generator.generate();
        for (String expected : List.of("`config.correlationKey`", "`config.lookback`", "`config.timeout`",
                "`isTimeout`", "`config.title`", "`+=`")) {
            assertTrue(ref.contains(expected), "reference must mention: " + expected);
        }
        String example = generator.examples().stream()
                .filter(e -> e.getKey().equals("workflows/issue-triage.json"))
                .map(e -> e.getValue()).findFirst().orElseThrow();
        for (String expected : List.of("\"correlationKey\"", "\"lookback\"", "\"timeout\"", "\"isTimeout\": true",
                "\"title\"")) {
            assertTrue(example.contains(expected), "example workflow must show: " + expected);
        }
        String prompt = mapper.readTree(getClass().getResourceAsStream(
                "/templates/assistant-templates/axiom-config-assistant.json")).path("systemPrompt").asText();
        assertTrue(prompt.contains("Receive-event nodes must correlate"), prompt);
    }

    @Test
    void nodeConfigDocsCoverEveryNodeType() {
        assertEquals(EnumSet.allOf(NodeType.class),
                EnumSet.copyOf(AssistantReferenceGenerator.NODE_CONFIG_DOCS.keySet()));
    }

    @Test
    void referenceListsEveryNodeAndEventType() {
        String ref = generator.generate();
        for (EventType t : EventType.values()) {
            assertTrue(ref.contains(t.value()), t.value());
        }
        for (String n : List.of("start", "end", "action", "human-task", "receive-event", "wait")) {
            assertTrue(ref.contains("`" + n + "`"), n);
        }
    }

    @Test
    void everyExampleValidatesWithoutProblems(@TempDir Path dir) throws Exception {
        copyExamples(dir);
        ObjectNode pack = AssistantPackBuilder.fromDirectory(dir, mapper);
        ValidationContext ctx = contextFactory.forPack(pack);
        for (Map.Entry<String, String> e : generator.examples()) {
            ConfigItemType type = ConfigItemType.fromName(e.getKey().substring(0, e.getKey().indexOf('/')));
            List<ConfigProblem> problems = validationService.validate(type, mapper.readTree(e.getValue()), ctx);
            // The example's secret (GH_TOKEN, mandated by the scenario) is not created in tests.
            List<ConfigProblem> unexpected = problems.stream()
                    .filter(p -> !"UNKNOWN_SECRET".equals(p.code()))
                    .toList();
            assertTrue(unexpected.isEmpty(), e.getKey() + ": " + unexpected);
        }
    }

    @Test
    void examplesApplyEndToEnd(@TempDir Path dir) throws Exception {
        copyExamples(dir);
        importExportService.importOrUpdatePack(AssistantPackBuilder.fromDirectory(dir, mapper));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity wf = WorkflowDefinitionEntity.find("name", "issue-triage").firstResult();
            assertNotNull(wf);
            assertNotNull(wf.currentVersion);
            EventSubscriptionEntity sub = EventSubscriptionEntity.find("name", "triage-new-issues").firstResult();
            assertTrue(sub.routing.contains("\"workflowDefinitionId\":" + wf.id), sub.routing);
        });
    }

    @Test
    void configAssistantPromptUsesReferencePlaceholder() throws Exception {
        String template = new String(getClass().getResourceAsStream(
                "/templates/assistant-templates/axiom-config-assistant.json").readAllBytes(),
                StandardCharsets.UTF_8);
        String prompt = mapper.readTree(template).path("systemPrompt").asText();
        assertTrue(prompt.contains("{{reference}}"));
        assertTrue(prompt.contains("axiom_validate_item"));
        assertFalse(prompt.contains("\"START\""));
        assertFalse(prompt.contains("sourceNodeId"));
        assertTrue(prompt.contains("Declare an input for everything an action operates on"));
        assertTrue(prompt.contains("Do not write output-format instructions in prompts"));
        String reference = generator.generate();
        int hints = reference.indexOf("Other former placeholders are rejected");
        assertTrue(hints > 0);
        assertFalse(reference.substring(0, hints).contains("{{ref}}"));
        assertTrue(reference.substring(hints).contains("{{ref}}"));
    }

    private void copyExamples(Path dir) throws IOException {
        for (Map.Entry<String, String> e : generator.examples()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
    }

    @Test
    void changeSetExampleUsesUnconditionalNonDefaultEdgesForSingleOutgoingEdges() {
        String reference = generator.generateChangeSetReference();
        org.junit.jupiter.api.Assertions.assertFalse(reference.contains("\"isDefault\": true"), reference);
        org.junit.jupiter.api.Assertions.assertTrue(generator.generate().contains(
                "Mark an edge as the default (`isDefault: true`) only when its node also has conditional edges"));
    }
}
