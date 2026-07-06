package io.apitomy.axiom.app.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InboxResponseValidator.
 */
class InboxResponseValidatorTest {

    private InboxResponseValidator validator;

    @BeforeEach
    void setUp() {
        validator = new InboxResponseValidator();
    }

    @Test
    void testNullSchemaSkipsValidation() {
        List<String> errors = validator.validate(null, Map.of("anything", "goes"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testBlankSchemaSkipsValidation() {
        List<String> errors = validator.validate("  ", Map.of("anything", "goes"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testEmptyFieldsArrayPassesValidation() {
        String schema = """
                { "fields": [] }
                """;
        List<String> errors = validator.validate(schema, Map.of());
        assertTrue(errors.isEmpty());
    }

    @Test
    void testRequiredFieldMissing() {
        String schema = """
                {
                    "fields": [
                        { "name": "approved", "type": "boolean", "label": "Approve?", "required": true }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of());

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("approved"));
        assertTrue(errors.getFirst().contains("required"));
    }

    @Test
    void testRequiredFieldBlankString() {
        String schema = """
                {
                    "fields": [
                        { "name": "name", "type": "text", "label": "Name", "required": true }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of("name", "   "));

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("name"));
    }

    @Test
    void testRequiredFieldPresent() {
        String schema = """
                {
                    "fields": [
                        { "name": "approved", "type": "boolean", "label": "Approve?", "required": true }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of("approved", true));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testOptionalFieldMissingIsAllowed() {
        String schema = """
                {
                    "fields": [
                        { "name": "notes", "type": "textarea", "label": "Notes", "required": false }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of());
        assertTrue(errors.isEmpty());
    }

    @Test
    void testBooleanTypeValidation() {
        String schema = """
                {
                    "fields": [
                        { "name": "flag", "type": "boolean", "label": "Flag", "required": false }
                    ]
                }
                """;

        List<String> errors = validator.validate(schema, Map.of("flag", "yes"));
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("boolean"));

        errors = validator.validate(schema, Map.of("flag", true));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testNumberTypeValidation() {
        String schema = """
                {
                    "fields": [
                        { "name": "count", "type": "number", "label": "Count", "required": false }
                    ]
                }
                """;

        List<String> errors = validator.validate(schema, Map.of("count", "five"));
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("number"));

        errors = validator.validate(schema, Map.of("count", 42));
        assertTrue(errors.isEmpty());

        errors = validator.validate(schema, Map.of("count", 3.14));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testSelectValidatesAgainstOptions() {
        String schema = """
                {
                    "fields": [
                        {
                            "name": "env",
                            "type": "select",
                            "label": "Environment",
                            "required": true,
                            "options": [
                                { "label": "Dev", "value": "dev" },
                                { "label": "Staging", "value": "staging" },
                                { "label": "Prod", "value": "prod" }
                            ]
                        }
                    ]
                }
                """;

        List<String> errors = validator.validate(schema, Map.of("env", "dev"));
        assertTrue(errors.isEmpty());

        errors = validator.validate(schema, Map.of("env", "prod"));
        assertTrue(errors.isEmpty());

        errors = validator.validate(schema, Map.of("env", "invalid-env"));
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("must be one of"));
    }

    @Test
    void testTextFieldAcceptsAnyString() {
        String schema = """
                {
                    "fields": [
                        { "name": "notes", "type": "text", "label": "Notes", "required": false }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of("notes", "anything at all"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testTextareaFieldAcceptsAnyString() {
        String schema = """
                {
                    "fields": [
                        { "name": "body", "type": "textarea", "label": "Body", "required": false }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of("body", "multi\nline\ntext"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testMultipleFieldsWithMixedErrors() {
        String schema = """
                {
                    "fields": [
                        { "name": "approved", "type": "boolean", "label": "Approve?", "required": true },
                        { "name": "count", "type": "number", "label": "Count", "required": true },
                        { "name": "notes", "type": "text", "label": "Notes", "required": false }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of("count", "not-a-number"));

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("approved") && e.contains("required")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("count") && e.contains("number")));
    }

    @Test
    void testExtraFieldsInResponseAreIgnored() {
        String schema = """
                {
                    "fields": [
                        { "name": "approved", "type": "boolean", "label": "Approve?", "required": true }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema,
                Map.of("approved", true, "extraField", "ignored"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testInvalidSchemaJsonReturnsError() {
        List<String> errors = validator.validate("not valid json", Map.of());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("Invalid output schema"));
    }

    @Test
    void testMissingFieldsKeyInSchemaPassesValidation() {
        String schema = """
                { "something": "else" }
                """;
        List<String> errors = validator.validate(schema, Map.of());
        assertTrue(errors.isEmpty());
    }

    @Test
    void testRequiredBooleanFalseIsValid() {
        String schema = """
                {
                    "fields": [
                        { "name": "agreed", "type": "boolean", "label": "Agree?", "required": true }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of("agreed", false));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testRequiredNumberZeroIsValid() {
        String schema = """
                {
                    "fields": [
                        { "name": "priority", "type": "number", "label": "Priority", "required": true }
                    ]
                }
                """;
        List<String> errors = validator.validate(schema, Map.of("priority", 0));
        assertTrue(errors.isEmpty());
    }
}
