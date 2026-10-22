package dev.floresm.transactions.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PostgreSQL {@code TIMESTAMPTZ} stores <b>microseconds</b>; Java's {@link Instant} can hold
 * <b>nanoseconds</b>. If we saved {@code Instant.now()} as is, the value read back would differ
 * from the one we kept in memory, which breaks equality checks and keyset pagination cursors.
 * Truncating at the source makes the round trip lossless.
 */
final class Timestamps {

    private Timestamps() {
    }

    static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
