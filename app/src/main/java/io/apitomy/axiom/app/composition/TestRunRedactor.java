package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.api.beans.CompositionTestEnvironment;
import io.apitomy.axiom.api.beans.CompositionTestRun;
import io.apitomy.axiom.api.beans.CompositionTestValues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the view of a test run that is shared with the workbench AI. Secrets never reach the AI: environment
 * values are replaced unless they are made only of {@code ${secret:NAME}} references (a reference names a secret
 * but does not reveal it), the values of referenced secrets are scrubbed from every text of the run, and stdout
 * and stderr are truncated.
 */
public final class TestRunRedactor {

    /** Replacement for a redacted value. */
    public static final String REDACTED = "[redacted]";

    /** Characters of stdout and of stderr kept in the AI view (the tail is kept). */
    public static final int MAX_LOG_CHARS = 8000;

    /** Secret values shorter than this are not scrubbed from text: they would match too much. */
    static final int MIN_SCRUB_LENGTH = 4;

    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([^}]+)}");

    private TestRunRedactor() {
    }

    /**
     * Lists the secrets an environment references.
     *
     * @param environment the raw environment, or {@code null}
     * @return the referenced secret names
     */
    public static Set<String> referencedSecrets(Map<String, String> environment) {
        Set<String> names = new LinkedHashSet<>();
        if (environment != null) {
            environment.values().forEach(value -> {
                Matcher matcher = SECRET_REF.matcher(value == null ? "" : value);
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            });
        }
        return names;
    }

    /**
     * Builds the AI view of a run.
     *
     * @param run          the stored run (not modified)
     * @param environment  the raw (unresolved) environment of the run, or {@code null}
     * @param secretValues the values of the secrets the environment references, scrubbed from all text
     * @return a redacted copy of the run
     */
    public static CompositionTestRun redact(CompositionTestRun run, Map<String, String> environment,
                                            Collection<String> secretValues) {
        List<String> scrub = secretValues.stream()
                .filter(v -> v != null && v.length() >= MIN_SCRUB_LENGTH)
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        CompositionTestRun copy = new CompositionTestRun();
        copy.setId(run.getId());
        copy.setItemId(run.getItemId());
        copy.setItemType(run.getItemType());
        copy.setItemName(run.getItemName());
        copy.setAuthor(run.getAuthor());
        copy.setTurnId(run.getTurnId());
        copy.setSubjectId(run.getSubjectId());
        copy.setStatus(run.getStatus());
        copy.setStartedAt(run.getStartedAt());
        copy.setDurationMs(run.getDurationMs());
        copy.setExitCode(run.getExitCode());
        copy.setExecutionMode(run.getExecutionMode());
        copy.setInputs(scrubValues(run.getInputs(), scrub));
        copy.setOutputs(scrubValues(run.getOutputs(), scrub));
        copy.setStdout(tail(scrubText(run.getStdout(), scrub)));
        copy.setStderr(tail(scrubText(run.getStderr(), scrub)));
        copy.setMessage(scrubText(run.getMessage(), scrub));
        copy.setResolvedScript(scrubText(run.getResolvedScript(), scrub));
        if (run.getProblems() != null) {
            List<String> problems = new ArrayList<>();
            run.getProblems().forEach(p -> problems.add(scrubText(p, scrub)));
            copy.setProblems(problems);
        }
        if (environment != null && !environment.isEmpty()) {
            CompositionTestEnvironment env = new CompositionTestEnvironment();
            environment.forEach((name, value) -> env.setAdditionalProperty(name, redactEnvValue(value)));
            copy.setEnvironment(env);
        }
        return copy;
    }

    /** Keeps a value made only of secret references; replaces anything else. */
    static String redactEnvValue(String value) {
        if (value != null && !value.isEmpty() && SECRET_REF.matcher(value).replaceAll("").isEmpty()) {
            return value;
        }
        return REDACTED;
    }

    static String tail(String text) {
        if (text == null || text.length() <= MAX_LOG_CHARS) {
            return text;
        }
        int dropped = text.length() - MAX_LOG_CHARS;
        return "[truncated " + dropped + " chars]\n" + text.substring(dropped);
    }

    static String scrubText(String text, List<String> scrub) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : scrub) {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }

    private static CompositionTestValues scrubValues(CompositionTestValues values, List<String> scrub) {
        if (values == null) {
            return null;
        }
        CompositionTestValues copy = new CompositionTestValues();
        Map<String, Object> source = new LinkedHashMap<>(values.getAdditionalProperties());
        source.forEach((k, v) -> copy.setAdditionalProperty(k, scrubObject(v, scrub)));
        return copy;
    }

    private static Object scrubObject(Object value, List<String> scrub) {
        if (value instanceof String s) {
            return scrubText(s, scrub);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, scrubObject(v, scrub)));
            return copy;
        }
        if (value instanceof Collection<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(scrubObject(v, scrub)));
            return copy;
        }
        return value;
    }
}
