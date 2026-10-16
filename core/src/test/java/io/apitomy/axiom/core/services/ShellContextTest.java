package io.apitomy.axiom.core.services;

import org.junit.jupiter.api.Test;

import static io.apitomy.axiom.core.services.ShellContext.Context.DOUBLE;
import static io.apitomy.axiom.core.services.ShellContext.Context.HEREDOC;
import static io.apitomy.axiom.core.services.ShellContext.Context.QUOTED_HEREDOC;
import static io.apitomy.axiom.core.services.ShellContext.Context.SINGLE;
import static io.apitomy.axiom.core.services.ShellContext.Context.UNQUOTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellContextTest {

    private static ShellContext.Context at(String script, String marker) {
        return ShellContext.analyze(script)[script.indexOf(marker)];
    }

    @Test
    void quotes() {
        String s = "echo X1 'X2' \"X3\" \\'X4";
        assertEquals(UNQUOTED, at(s, "X1"));
        assertEquals(SINGLE, at(s, "X2"));
        assertEquals(DOUBLE, at(s, "X3"));
        assertEquals(UNQUOTED, at(s, "X4"));
    }

    @Test
    void unquotedHeredocBodyAndTerminator() {
        String s = "cat <<EOF > f\nBODY \"q\" 'q'\nEOF\nAFTER\n";
        assertEquals(UNQUOTED, at(s, "> f"));
        assertEquals(HEREDOC, at(s, "BODY"));
        assertEquals(HEREDOC, at(s, "'q'"));
        assertEquals(UNQUOTED, at(s, "EOF\nAFTER"));
        assertEquals(UNQUOTED, at(s, "AFTER"));
    }

    @Test
    void quotedDelimitersMakeQuotedHeredocs() {
        for (String op : new String[] {"<<'EOF'", "<<\"EOF\"", "<<\\EOF", "<< 'EOF'", "<<E'O'F"}) {
            String s = "cat " + op + "\nBODY\nEOF\nAFTER";
            assertEquals(QUOTED_HEREDOC, at(s, "BODY"), op);
            assertEquals(UNQUOTED, at(s, "AFTER"), op);
        }
    }

    @Test
    void dashHeredocAllowsIndentedTerminator() {
        String s = "cat <<-EOF\n\tBODY\n\tEOF\nAFTER";
        assertEquals(HEREDOC, at(s, "BODY"));
        assertEquals(UNQUOTED, at(s, "AFTER"));
    }

    @Test
    void terminatorMustMatchWholeLine() {
        String s = "cat <<EOF\nEOF2\nIN\nEOF\nAFTER";
        assertEquals(HEREDOC, at(s, "IN"));
        assertEquals(UNQUOTED, at(s, "AFTER"));
    }

    @Test
    void twoHeredocsOnOneLine() {
        String s = "paste <<A <<'B'\nONE\nA\nTWO\nB\nAFTER";
        assertEquals(HEREDOC, at(s, "ONE"));
        assertEquals(QUOTED_HEREDOC, at(s, "TWO"));
        assertEquals(UNQUOTED, at(s, "AFTER"));
    }

    @Test
    void hereStringAndArithmeticShiftAreNotHeredocs() {
        String s = "jq . <<< X1\necho $((1<<2)) X2\nX3";
        assertEquals(UNQUOTED, at(s, "X1"));
        assertEquals(UNQUOTED, at(s, "X2"));
        assertEquals(UNQUOTED, at(s, "X3"));
    }

    @Test
    void heredocOperatorInsideQuotesIsIgnored() {
        String s = "echo \"<<EOF\"\nX1";
        assertEquals(UNQUOTED, at(s, "X1"));
    }
}
