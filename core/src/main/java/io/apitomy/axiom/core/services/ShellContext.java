package io.apitomy.axiom.core.services;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Classifies every character of a bash script by the quoting context it appears in, including heredoc bodies.
 * Used to substitute {@code {{inputs.X}}} placeholders with a correctly formed shell reference and to reject
 * placeholders where bash never expands anything (quoted heredoc bodies).
 *
 * <p>Handles single and double quotes, backslash escapes, heredocs ({@code <<WORD}, {@code <<-WORD}, with a
 * quoted or unquoted delimiter, several per line), and ignores {@code <<<} here-strings and {@code <<} inside
 * arithmetic ({@code $(( ))}, {@code (( ))}). It is a pragmatic scanner for templates, not a full bash parser.</p>
 */
public final class ShellContext {

    /** The quoting context of a character. */
    public enum Context {
        /** Outside any quotes. */
        UNQUOTED,
        /** Inside single quotes: nothing expands. */
        SINGLE,
        /** Inside double quotes: parameters expand. */
        DOUBLE,
        /** Inside the body of a heredoc with an unquoted delimiter: parameters expand, quotes are literal. */
        HEREDOC,
        /** Inside the body of a heredoc with a quoted delimiter: nothing expands. */
        QUOTED_HEREDOC
    }

    private record PendingHeredoc(String delimiter, boolean quoted, boolean stripTabs) {
    }

    private ShellContext() {
    }

    /**
     * Analyzes a script.
     *
     * @param script the script text
     * @return the context of each character, indexed like {@code script}
     */
    public static Context[] analyze(String script) {
        int n = script.length();
        Context[] contexts = new Context[n];
        Arrays.fill(contexts, Context.UNQUOTED);
        Deque<PendingHeredoc> pending = new ArrayDeque<>();
        char quote = 0;
        int arithmeticDepth = 0;
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            if (quote == '\'') {
                contexts[i] = Context.SINGLE;
                if (c == '\'') {
                    contexts[i] = Context.UNQUOTED;
                    quote = 0;
                }
                i++;
                continue;
            }
            if (quote == '"') {
                contexts[i] = Context.DOUBLE;
                if (c == '\\' && i + 1 < n) {
                    contexts[i + 1] = Context.DOUBLE;
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    contexts[i] = Context.UNQUOTED;
                    quote = 0;
                }
                i++;
                continue;
            }
            // Unquoted
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                i++;
                continue;
            }
            if (c == '(' && i + 1 < n && script.charAt(i + 1) == '(') {
                arithmeticDepth++;
                i += 2;
                continue;
            }
            if (c == ')' && arithmeticDepth > 0 && i + 1 < n && script.charAt(i + 1) == ')') {
                arithmeticDepth--;
                i += 2;
                continue;
            }
            if (script.startsWith("<<<", i)) {
                i += 3;
                continue;
            }
            if (c == '<' && arithmeticDepth == 0 && script.startsWith("<<", i)) {
                i = parseHeredocOperator(script, i + 2, pending);
                continue;
            }
            if (c == '\n' && !pending.isEmpty()) {
                i = consumeBodies(script, i + 1, pending, contexts);
                continue;
            }
            i++;
        }
        return contexts;
    }

    /** Parses {@code [-][blanks]WORD} after {@code <<}; returns the index after the word. */
    private static int parseHeredocOperator(String s, int i, Deque<PendingHeredoc> pending) {
        int n = s.length();
        boolean stripTabs = false;
        if (i < n && s.charAt(i) == '-') {
            stripTabs = true;
            i++;
        }
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        StringBuilder delimiter = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    delimiter.append(c);
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                quoted = true;
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                quoted = true;
                delimiter.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (Character.isWhitespace(c) || ";|&<>()".indexOf(c) >= 0) {
                break;
            }
            delimiter.append(c);
            i++;
        }
        if (!delimiter.isEmpty()) {
            pending.addLast(new PendingHeredoc(delimiter.toString(), quoted, stripTabs));
        }
        return i;
    }

    /** Marks heredoc bodies starting at {@code i}; returns the index after the last terminator line. */
    private static int consumeBodies(String s, int i, Deque<PendingHeredoc> pending, Context[] contexts) {
        int n = s.length();
        while (!pending.isEmpty() && i < n) {
            PendingHeredoc doc = pending.peekFirst();
            int lineEnd = s.indexOf('\n', i);
            int end = lineEnd < 0 ? n : lineEnd;
            String line = s.substring(i, end);
            String candidate = doc.stripTabs() ? line.replaceFirst("^\t+", "") : line;
            if (candidate.equals(doc.delimiter())) {
                pending.removeFirst();
            } else {
                Context body = doc.quoted() ? Context.QUOTED_HEREDOC : Context.HEREDOC;
                Arrays.fill(contexts, i, end, body);
            }
            i = lineEnd < 0 ? n : lineEnd + 1;
        }
        return i;
    }
}
