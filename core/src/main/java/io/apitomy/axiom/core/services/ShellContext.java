package io.apitomy.axiom.core.services;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Classifies every character of a bash script by the quoting context it appears in, including heredoc bodies.
 * Used to substitute {@code {{inputs.X}}} placeholders with a correctly formed shell reference and to reject
 * placeholders where bash never expands anything (quoted heredoc bodies and comments).
 *
 * <p>Handles single and double quotes, backslash escapes, heredocs ({@code <<WORD}, {@code <<-WORD}, with a
 * quoted or unquoted delimiter, several per line), and comments: an unquoted {@code #} at the start of a word
 * starts a comment that runs to the end of the line without changing quote state. It ignores {@code <<<}
 * here-strings and treats {@code #} inside arithmetic ({@code $(( ))}, {@code (( ))}) as literal. A {@code #}
 * right after a closing {@code $( )} or {@code $(( ))}, or after a backslash-newline that joins it to the
 * preceding word, is glued to that word and does not start a comment. It is a
 * pragmatic scanner for templates, not a full bash parser.</p>
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
        QUOTED_HEREDOC,
        /** Inside a comment, from the {@code #} to the end of the line: bash discards it before expansion. */
        COMMENT
    }

    private record PendingHeredoc(String delimiter, boolean quoted, boolean stripTabs) {
    }

    /** The characters where bash ends one word and can start another. */
    private static final String SHELL_WORD_BOUNDARIES = ";|&<>()";

    /** True where bash ends one word and can start another: whitespace or a shell metacharacter. */
    private static boolean isShellWordBoundary(char c) {
        return Character.isWhitespace(c) || SHELL_WORD_BOUNDARIES.indexOf(c) >= 0;
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
        // One entry per open "((" / "(": true when it was opened with '$', i.e. it is an expansion
        // that stays part of the surrounding word once closed.
        Deque<Boolean> arithmetic = new ArrayDeque<>();
        Deque<Boolean> parens = new ArrayDeque<>();
        // Index at which a '#' is glued to the preceding word even though the previous character is
        // a word boundary: right after a closing $( ) or $(( )), or after a line continuation.
        int gluedAt = -1;
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
                // bash removes a backslash-newline before splitting words, so whatever follows
                // continues the word that preceded the backslash.
                if (script.charAt(i + 1) == '\n'
                        && (gluedAt == i || (i > 0 && !isShellWordBoundary(script.charAt(i - 1))))) {
                    gluedAt = i + 2;
                }
                i += 2;
                continue;
            }
            // A '#' at the start of a word comments out the rest of the line; bash never sees
            // quotes inside it, so quote state must not change. Inside arithmetic a '#' is a
            // literal (base notation, operators), not a comment.
            if (c == '#' && arithmetic.isEmpty() && i != gluedAt && startsComment(script, i)) {
                while (i < n && script.charAt(i) != '\n') {
                    contexts[i] = Context.COMMENT;
                    i++;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                i++;
                continue;
            }
            boolean dollar = i > 0 && script.charAt(i - 1) == '$';
            if (c == '(' && i + 1 < n && script.charAt(i + 1) == '(') {
                arithmetic.push(dollar);
                i += 2;
                continue;
            }
            if (c == ')' && !arithmetic.isEmpty() && i + 1 < n && script.charAt(i + 1) == ')') {
                if (arithmetic.pop()) {
                    gluedAt = i + 2;
                }
                i += 2;
                continue;
            }
            if (c == '(') {
                parens.push(dollar);
            } else if (c == ')' && !parens.isEmpty() && parens.pop()) {
                gluedAt = i + 1;
            }
            if (script.startsWith("<<<", i)) {
                i += 3;
                continue;
            }
            if (c == '<' && arithmetic.isEmpty() && script.startsWith("<<", i)) {
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

    /** True when the {@code #} at {@code i} starts a comment: it must open a word, that is, sit at the
     * start of the text or right after a word boundary. A {@code #} glued to a preceding word or closing
     * quote is literal ({@code a#b}, {@code 'x'#y}, {@code ${#var}}). */
    private static boolean startsComment(String script, int i) {
        return i == 0 || isShellWordBoundary(script.charAt(i - 1));
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
            if (isShellWordBoundary(c)) {
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
