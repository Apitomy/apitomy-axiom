package io.apitomy.axiom.app.summary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunSummaryResponseParserTest {

    @Test
    void parsesPlainJson() {
        RunSummaryResponseParser.ParsedSummary s =
                RunSummaryResponseParser.parse("{\"headline\":\"H\",\"body\":\"- a\"}");
        assertEquals("H", s.headline());
        assertEquals("- a", s.body());
    }

    @Test
    void parsesFencedJsonWithChatter() {
        String out = "Here you go:\n```json\n{\"headline\": \"Fenced\", \"body\": \"- {x}\"}\n```\nDone.";
        assertEquals("Fenced", RunSummaryResponseParser.parse(out).headline());
    }

    @Test
    void bodyIsOptional() {
        assertNull(RunSummaryResponseParser.parse("{\"headline\":\"Only\"}").body());
    }

    @Test
    void truncatesLongHeadlineTo200() {
        String h = "x".repeat(300);
        assertEquals(200, RunSummaryResponseParser.parse("{\"headline\":\"" + h + "\"}").headline().length());
    }

    @Test
    void rejectsMissingHeadlineOrNoJson() {
        assertThrows(IllegalArgumentException.class, () -> RunSummaryResponseParser.parse("{\"body\":\"b\"}"));
        assertThrows(IllegalArgumentException.class, () -> RunSummaryResponseParser.parse("no json here"));
        assertThrows(IllegalArgumentException.class, () -> RunSummaryResponseParser.parse(null));
    }
}
