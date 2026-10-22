package io.apitomy.axiom.events.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestTallyTest {

    @Test
    void countsPersistedAndInvalid() {
        IngestTally tally = new IngestTally();
        tally.record(PersistOutcome.PERSISTED);
        tally.record(PersistOutcome.PERSISTED_INVALID);
        tally.record(PersistOutcome.DUPLICATE);
        assertEquals(2, tally.ingested());
        assertEquals(1, tally.invalid());
        assertEquals(" (1 failed schema validation)", tally.validationSuffix());
    }

    @Test
    void noSuffixWhenAllValid() {
        IngestTally tally = new IngestTally();
        tally.record(PersistOutcome.PERSISTED);
        assertEquals("", tally.validationSuffix());
    }
}
