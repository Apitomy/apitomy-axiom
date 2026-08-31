package io.apitomy.axiom.core.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputBindingResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseInputsReturnsEmptyForNullOrBlank() {
        assertEquals(Map.of(), InputBindingResolver.parseInputs(null, mapper));
        assertEquals(Map.of(), InputBindingResolver.parseInputs("   ", mapper));
    }

    @Test
    void parseInputsReturnsEmptyForNonObjectJson() {
        assertEquals(Map.of(), InputBindingResolver.parseInputs("not json", mapper));
        assertEquals(Map.of(), InputBindingResolver.parseInputs("[1,2,3]", mapper));
    }

    @Test
    void parseInputsParsesJsonObject() {
        Map<String, Object> parsed = InputBindingResolver.parseInputs("{\"repo\":\"acme/app\",\"count\":3}", mapper);
        assertEquals("acme/app", parsed.get("repo"));
        assertEquals(3, parsed.get("count"));
    }

    @Test
    void bindInputsSubstitutesStringPlaceholder() {
        String result = InputBindingResolver.bindInputs(
                "Repo is {{inputs.repo}}.", Map.of("repo", "acme/app"), mapper);
        assertEquals("Repo is acme/app.", result);
    }

    @Test
    void bindInputsRendersNonStringAsJson() {
        String result = InputBindingResolver.bindInputs(
                "count={{inputs.count}}", Map.of("count", 42), mapper);
        assertEquals("count=42", result);
    }

    @Test
    void bindInputsRendersNullAsEmptyString() {
        // Map.of does not allow null values, so use a mutable map via renderValue directly.
        assertEquals("", InputBindingResolver.renderValue(null, mapper));
    }

    @Test
    void bindInputsIsNoopForNullOrEmptyInputs() {
        assertEquals("template", InputBindingResolver.bindInputs("template", Map.of(), mapper));
        assertEquals("template", InputBindingResolver.bindInputs("template", null, mapper));
        assertEquals(null, InputBindingResolver.bindInputs(null, Map.of("a", "b"), mapper));
    }

    @Test
    void buildOutputContractListsDeclaredOutputs() {
        List<ActionTypeField> outputs = List.of(
                new ActionTypeField("summary", "string", true, "A short summary"),
                new ActionTypeField("count", "number", false, null));
        String contract = InputBindingResolver.buildOutputContract(outputs);
        assertTrue(contract.contains("## Required output"));
        assertTrue(contract.contains("\"summary\" (string) [required] — A short summary"));
        assertTrue(contract.contains("\"count\" (number)"));
    }
}
