package dev.floresm.transactions.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The PostgreSQL JDBC driver maps {@code TIMESTAMPTZ} to {@link OffsetDateTime}, not to
 * {@link Instant}. These helpers do the conversion in one place, always in UTC.
 */
final class SqlTime {

    private SqlTime() {
    }

    static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant fromDb(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
