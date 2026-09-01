package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.HumanContext;
import io.apitomy.axiom.api.beans.OutputSchema;
import io.apitomy.axiom.api.beans.OutputSchemaField;
import io.apitomy.flow.model.HumanTaskInfo;
import io.apitomy.flow.model.OutputDefinition;
import io.apitomy.flow.model.OutputOption;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowHumanTaskMapperTest {

    @Test
    void toHumanContextUsesNodeNameAndDescriptionAndDetails() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("Credit score", 720);
        inputs.put("Applicant", "Ada");
        HumanTaskInfo hti = new HumanTaskInfo("ht1", "Approve loan", "Review the application",
                inputs, List.of());

        HumanContext ctx = WorkflowHumanTaskMapper.toHumanContext(hti);

        assertEquals("Approve loan", ctx.getTitle());
        assertEquals("Review the application", ctx.getDescription());
        assertEquals(2, ctx.getDetails().size());
        assertEquals("Credit score", ctx.getDetails().get(0).getLabel());
        assertEquals("720", ctx.getDetails().get(0).getValue());
    }

    @Test
    void toHumanContextFallsBackToDefaultTitle() {
        HumanTaskInfo hti = new HumanTaskInfo("ht1", "  ", null, Map.of(), List.of());
        HumanContext ctx = WorkflowHumanTaskMapper.toHumanContext(hti);
        assertEquals("Human Task", ctx.getTitle());
    }

    @Test
    void toOutputSchemaMapsWidgetsTypesOptionsAndDefault() {
        OutputDefinition approve = new OutputDefinition(
                "approved", "boolean", true, "Approve?", "Check to approve", "checkbox", true, null);
        OutputDefinition tier = new OutputDefinition(
                "tier", "string", false, "Tier", null, "select", "gold",
                List.of(new OutputOption("Gold", "gold"), new OutputOption("Silver", "silver")));
        OutputDefinition notes = new OutputDefinition(
                "notes", "string", false, "Notes", null, "textarea", null, null);

        OutputSchema schema = WorkflowHumanTaskMapper.toOutputSchema(List.of(approve, tier, notes));

        assertEquals(3, schema.getFields().size());
        OutputSchemaField f0 = schema.getFields().get(0);
        assertEquals("approved", f0.getName());
        assertEquals(OutputSchemaField.Type.BOOLEAN, f0.getType());
        assertEquals("Approve?", f0.getLabel());
        assertTrue(f0.getRequired());
        assertEquals(Boolean.TRUE, f0.getDefaultValue());

        OutputSchemaField f1 = schema.getFields().get(1);
        assertEquals(OutputSchemaField.Type.SELECT, f1.getType());
        assertEquals(2, f1.getOptions().size());
        assertEquals("gold", f1.getOptions().get(0).getValue());

        assertEquals(OutputSchemaField.Type.TEXTAREA, schema.getFields().get(2).getType());
    }

    @Test
    void toOutputSchemaReturnsNullWhenNoOutputs() {
        assertNull(WorkflowHumanTaskMapper.toOutputSchema(List.of()));
        assertNull(WorkflowHumanTaskMapper.toOutputSchema(null));
    }

    @Test
    void coerceAnswersConvertsToSemanticTypes() {
        List<OutputDefinition> outputs = List.of(
                new OutputDefinition("score", "number", false),
                new OutputDefinition("approved", "boolean", false),
                new OutputDefinition("meta", "object", false),
                new OutputDefinition("comment", "string", false));

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("score", "720");
        answers.put("approved", "true");
        answers.put("meta", "{\"k\":1}");
        answers.put("comment", 42);

        Map<String, Object> coerced = WorkflowHumanTaskMapper.coerceAnswers(outputs, answers);

        assertEquals(720L, coerced.get("score"));
        assertEquals(Boolean.TRUE, coerced.get("approved"));
        assertTrue(coerced.get("meta") instanceof Map);
        assertEquals("42", coerced.get("comment"));
    }

    @Test
    void coerceAnswersSkipsMissingAndKeepsNativeTypes() {
        List<OutputDefinition> outputs = List.of(
                new OutputDefinition("score", "number", false),
                new OutputDefinition("absent", "string", false));
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("score", 3.5);

        Map<String, Object> coerced = WorkflowHumanTaskMapper.coerceAnswers(outputs, answers);

        assertEquals(3.5, coerced.get("score"));
        assertTrue(!coerced.containsKey("absent"));
    }
}
