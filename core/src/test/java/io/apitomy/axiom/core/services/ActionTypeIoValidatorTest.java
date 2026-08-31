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
