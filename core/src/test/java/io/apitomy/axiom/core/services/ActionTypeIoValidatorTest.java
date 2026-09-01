package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ActionTypeField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ActionTypeIoValidatorTest {

    @Test
    void missingRequiredFieldReportsError() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("repo", "string", true, null));
        List<String> errors = ActionTypeIoValidator.validate(declared, Map.of());
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("repo"));
    }

    @Test
    void wrongTypeReportsError() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("count", "number", true, null));
        List<String> errors = ActionTypeIoValidator.validate(declared, Map.of("count", "not-a-number"));
        assertFalse(errors.isEmpty());
    }

    @Test
    void numericAcceptedForNumberType() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("count", "number", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("count", 42)));
    }

    @Test
    void numericStringCoercedToNumber() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("count", "number", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("count", "42")));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("count", "3.14")));
    }

    @Test
    void booleanStringCoercedToBoolean() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("flag", "boolean", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("flag", "true")));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("flag", "FALSE")));
    }

    @Test
    void nonBooleanStringReportsError() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("flag", "boolean", true, null));
        assertFalse(ActionTypeIoValidator.validate(declared, Map.of("flag", "yes")).isEmpty());
    }

    @Test
    void numberCoercedToString() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("id", "string", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("id", 42)));
    }

    @Test
    void jsonStringCoercedToObject() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("cfg", "object", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("cfg", "{\"a\":1}")));
    }

    @Test
    void nonObjectStringReportsError() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("cfg", "object", true, null));
        assertFalse(ActionTypeIoValidator.validate(declared, Map.of("cfg", "not-json")).isEmpty());
    }

    @Test
    void optionalMissingFieldIsOk() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("note", "string", false, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of()));
    }

    @Test
    void extraKeysAreAllowed() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("a", "string", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("a", "x", "b", "y")));
    }

    @Test
    void nullOrEmptyDeclaredIsAlwaysValid() {
        assertEquals(List.of(), ActionTypeIoValidator.validate(null, Map.of("x", 1)));
        assertEquals(List.of(), ActionTypeIoValidator.validate(List.of(), Map.of("x", 1)));
    }
}
