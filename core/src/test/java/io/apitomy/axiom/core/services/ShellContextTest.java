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
    void apostropheInCommentDoesNotQuoteFollowingLines() {
        String s = "# sync-target's flag\nX1 {{inputs.x}}\n";
        assertEquals(UNQUOTED, at(s, "X1"));
        assertEquals(UNQUOTED, at(s, "{{inputs.x}}"));
    }

    @Test
    void commentEndsAtNewlineAndQuoteStateIsUnchanged() {
        String s = "# don't stop\nX1 'z1'\n# said \"go\"\nX2 \"z2\"\nX3";
        assertEquals(UNQUOTED, at(s, "X1"));
        assertEquals(SINGLE, at(s, "z1"));
        assertEquals(UNQUOTED, at(s, "X2"));
        assertEquals(DOUBLE, at(s, "z2"));
        assertEquals(UNQUOTED, at(s, "X3"));
    }

    @Test
    void commentStartsOnlyAtWordStart() {
        String s = ";#'a\n&#'b\n|#'c\n(#'d\n)#'e\n>#'f\n<#'g\nX1";
        assertEquals(UNQUOTED, at(s, "X1"));
    }

    @Test
    void hashGluedToAWordIsNotAComment() {
        String s = "echo ${#var} a#b 'x'#y\nX1\n";
        assertEquals(UNQUOTED, at(s, "${#var}"));
        assertEquals(UNQUOTED, at(s, "a#b"));
        assertEquals(UNQUOTED, at(s, "'x'#y"));
        assertEquals(UNQUOTED, at(s, "X1"));
    }

    @Test
    void hashInsideQuotesIsNotAComment() {
        String s = "X1 '#p' X2 \"#q\" X3 {{inputs.x}}";
        assertEquals(SINGLE, at(s, "#p"));
        assertEquals(DOUBLE, at(s, "#q"));
        assertEquals(UNQUOTED, at(s, "X1"));
        assertEquals(UNQUOTED, at(s, "X3"));
        assertEquals(UNQUOTED, at(s, "{{inputs.x}}"));
    }

    @Test
    void hashInsideArithmeticIsLiteral() {
        String s = "X1=$(( 2#10 ))\nX2=$(( 3 << 1 ))\nX3";
        assertEquals(UNQUOTED, at(s, "2#10"));
        assertEquals(UNQUOTED, at(s, "3 << 1"));
        assertEquals(UNQUOTED, at(s, "X2"));
        assertEquals(UNQUOTED, at(s, "X3"));
    }

    @Test
    void hashInsideHeredocBodiesIsBodyText() {
        String s = "cat <<A\n# not a comment X1\nA\ncat <<'B'\n# still body X2\nB\nX3";
        assertEquals(HEREDOC, at(s, "# not a comment"));
        assertEquals(QUOTED_HEREDOC, at(s, "# still body"));
        assertEquals(UNQUOTED, at(s, "X3"));
    }

    @Test
    void heredocOperatorLineCanCarryAComment() {
        String s = "cat <<EOF # body follows, don't worry\nX1\nEOF\nX2";
        assertEquals(HEREDOC, at(s, "X1"));
        assertEquals(UNQUOTED, at(s, "X2"));
    }

    @Test
    void heredocOperatorInsideQuotesIsIgnored() {
        String s = "echo \"<<EOF\"\nX1";
        assertEquals(UNQUOTED, at(s, "X1"));
    }
}
