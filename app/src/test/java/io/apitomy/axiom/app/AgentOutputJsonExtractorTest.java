package io.apitomy.axiom.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentOutputJsonExtractor}.
 */
class AgentOutputJsonExtractorTest {

    @Test
    void returnsPlainJsonUnchanged() {
        String json = "{\"a\":1}";
        assertEquals(json, AgentOutputJsonExtractor.extract(json));
    }

    @Test
    void unwrapsFencedJson() {
        String output = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", AgentOutputJsonExtractor.extract(output));
    }

    @Test
    void ignoresBracesInProseBeforeFencedJson() {
        String output = "Placeholders like `{{inputs.x}}` are substituted.\n\n"
                + "```json\n{\"chosenLabels\": [\"type/bug\"], \"reasoning\": \"uses {{x}}\"}\n```";
        assertEquals("{\"chosenLabels\": [\"type/bug\"], \"reasoning\": \"uses {{x}}\"}",
                AgentOutputJsonExtractor.extract(output));
    }

    @Test
    void ignoresBracesInProseWithoutFence() {
        String output = "The `{{inputs.x}}` placeholder is broken. Result: {\"a\": \"b}\"} done.";
        assertEquals("{\"a\": \"b}\"}", AgentOutputJsonExtractor.extract(output));
    }

    @Test
    void prefersLastFencedBlock() {
        String output = "```json\n{\"draft\":true}\n```\nFinal:\n```json\n{\"draft\":false}\n```";
        assertEquals("{\"draft\":false}", AgentOutputJsonExtractor.extract(output));
    }

    @Test
    void returnsOuterObjectWhenNested() {
        String output = "Note {x}. Result: {\"a\": {\"b\": 1}, \"c\": [{\"d\": 2}]}";
        assertEquals("{\"a\": {\"b\": 1}, \"c\": [{\"d\": 2}]}",
                AgentOutputJsonExtractor.extract(output));
    }

    @Test
    void returnsOriginalWhenNoObjectFound() {
        String output = "no json here {{nope}}";
        assertEquals(output, AgentOutputJsonExtractor.extract(output));
    }
}
