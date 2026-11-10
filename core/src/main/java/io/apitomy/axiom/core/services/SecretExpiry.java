package io.apitomy.axiom.core.services;

import java.time.Duration;
import java.time.Instant;

/**
 * Computes a secret's expiry status. Pure functions; callers supply "now".
 */
public final class SecretExpiry {

    /** Expiry status of a secret. */
    public enum Status {
        /** No expiration set. */
        NONE,
        /** Expires later than its warning window. */
        VALID,
        /** Inside its warning window but not yet expired. */
        EXPIRING,
        /** At or past its expiration. */
        EXPIRED
    }

    private SecretExpiry() {
    }

    /**
     * Returns the status of a secret.
     *
     * @param expiresOn       the expiration, or null
     * @param warnDaysBefore  the per-secret warning window in days, or null for the default
     * @param defaultWarnDays the global warning window in days
     * @param now             the current time
     * @return the status
     */
    public static Status status(Instant expiresOn, Integer warnDaysBefore, int defaultWarnDays, Instant now) {
        if (expiresOn == null) {
            return Status.NONE;
        }
        if (!now.isBefore(expiresOn)) {
            return Status.EXPIRED;
        }
        int warnDays = warnDaysBefore != null ? warnDaysBefore : defaultWarnDays;
        Instant warningStart = expiresOn.minus(Duration.ofDays(warnDays));
        return now.isBefore(warningStart) ? Status.VALID : Status.EXPIRING;
    }

    /**
     * Returns whole days until expiry, rounded toward zero; negative once expired.
     *
     * @param expiresOn the expiration
     * @param now       the current time
     * @return the days remaining
     */
    public static long daysRemaining(Instant expiresOn, Instant now) {
        return Duration.between(now, expiresOn).toDays();
    }
}
