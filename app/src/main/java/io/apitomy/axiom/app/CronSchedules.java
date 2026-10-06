package io.apitomy.axiom.app;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Parses and evaluates Unix (5-field) cron expressions in UTC.
 */
public final class CronSchedules {

    private static final CronParser PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private CronSchedules() {
    }

    /**
     * Validates a cron expression.
     *
     * @param cron the expression
     * @throws IllegalArgumentException if the expression is invalid
     */
    public static void validate(String cron) {
        PARSER.parse(cron).validate();
    }

    /**
     * Computes the next execution strictly after the given instant.
     *
     * @param cron  the expression
     * @param after the reference instant
     * @return the next execution instant
     * @throws IllegalArgumentException if the expression is invalid or never fires
     */
    public static Instant nextAfter(String cron, Instant after) {
        ExecutionTime executionTime = ExecutionTime.forCron(PARSER.parse(cron));
        Optional<ZonedDateTime> next = executionTime.nextExecution(after.atZone(ZoneOffset.UTC));
        return next.map(ZonedDateTime::toInstant)
                .orElseThrow(() -> new IllegalArgumentException("Cron never fires: " + cron));
    }
}
