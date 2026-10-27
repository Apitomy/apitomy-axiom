package io.apitomy.axiom.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Best-effort extraction of a single JSON object from agent output. Agents are instructed to
 * emit only a JSON object, but frequently wrap it in Markdown code fences or surround it with
 * prose that may itself contain braces (e.g. {@code {{inputs.x}}} placeholders).
 */
final class AgentOutputJsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FENCE = Pattern.compile(
            "```(?:json|JSON)?[ \\t]*\\r?\\n(.*?)\\r?\\n?```", Pattern.DOTALL);

    private AgentOutputJsonExtractor() {
    }

    /**
     * Extracts the JSON object most likely intended as the agent's result. Fenced code blocks
     * are tried last-to-first, then balanced {@code {...}} spans in the whole output, scanning
     * backwards from the end. The first candidate that parses as a JSON object wins.
     *
     * @param output the raw agent output
     * @return the extracted JSON object text, or {@code output} unchanged when none is found
     */
    static String extract(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.strip();
        if (isJsonObject(trimmed)) {
            return trimmed;
        }
        List<String> fenced = new ArrayList<>();
        Matcher m = FENCE.matcher(output);
        while (m.find()) {
            fenced.add(m.group(1).strip());
        }
        for (int i = fenced.size() - 1; i >= 0; i--) {
            if (isJsonObject(fenced.get(i))) {
                return fenced.get(i);
            }
        }
        for (int start = output.lastIndexOf('{'); start >= 0;
                start = start == 0 ? -1 : output.lastIndexOf('{', start - 1)) {
            int end = findBalancedEnd(output, start);
            if (end > start) {
                String candidate = output.substring(start, end + 1);
                if (isJsonObject(candidate)) {
                    return outermost(output, start, candidate);
                }
            }
        }
        return output;
    }

    /**
     * Scanning backwards returns the innermost object first; widen to an enclosing object if
     * one also parses (so nested results like {"a":{"b":1}} return the outer object).
     */
    private static String outermost(String output, int start, String best) {
        for (int s = start == 0 ? -1 : output.lastIndexOf('{', start - 1); s >= 0;
                s = s == 0 ? -1 : output.lastIndexOf('{', s - 1)) {
            int end = findBalancedEnd(output, s);
            if (end > s) {
                String candidate = output.substring(s, end + 1);
                if (candidate.contains(best) && isJsonObject(candidate)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Returns the index of the brace closing the one at {@code start}, honouring JSON string
     * literals, or -1 when unbalanced.
     */
    private static int findBalancedEnd(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
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
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isJsonObject(String candidate) {
        if (!candidate.startsWith("{")) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(candidate);
            return node != null && node.isObject();
        } catch (Exception e) {
            return false;
        }
    }
}
