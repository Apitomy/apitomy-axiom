package io.apitomy.axiom.app.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Extracts the {@code {headline, body}} JSON object from an agent's free-form output. */
public final class RunSummaryResponseParser {

    /** Maximum stored headline length (matches the column). */
    public static final int MAX_HEADLINE = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A parsed summary.
     *
     * @param headline the one-line headline
     * @param body the markdown body, or null
     */
    public record ParsedSummary(String headline, String body) { }

    private RunSummaryResponseParser() { }

    /**
     * Parses the first JSON object with a non-blank headline from the agent output, skipping other
     * JSON objects (e.g. echoed tool-call arguments).
     *
     * @param agentOutput the raw output
     * @return the parsed summary
     * @throws IllegalArgumentException if no JSON object with a non-blank headline is found
     */
    public static ParsedSummary parse(String agentOutput) {
        if (agentOutput == null || agentOutput.isBlank()) {
            throw new IllegalArgumentException("The agent returned no output");
        }
        boolean sawJsonObject = false;
        for (int start = agentOutput.indexOf('{'); start >= 0; start = agentOutput.indexOf('{', start + 1)) {
            int end = matchingBrace(agentOutput, start);
            if (end < 0) {
                continue;
            }
            JsonNode node = tryRead(agentOutput.substring(start, end + 1));
            if (node != null && node.isObject()) {
                sawJsonObject = true;
                String headline = node.path("headline").asText("").trim();
                if (headline.isEmpty()) {
                    continue;
                }
                if (headline.length() > MAX_HEADLINE) {
                    headline = headline.substring(0, MAX_HEADLINE);
                }
                String body = node.hasNonNull("body") ? node.get("body").asText().trim() : null;
                return new ParsedSummary(headline, body == null || body.isEmpty() ? null : body);
            }
        }
        if (sawJsonObject) {
            throw new IllegalArgumentException("The agent's JSON has no headline");
        }
        throw new IllegalArgumentException("The agent did not return a JSON summary");
    }

    private static JsonNode tryRead(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** Finds the brace closing the one at {@code start}, ignoring braces inside JSON strings. */
    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
