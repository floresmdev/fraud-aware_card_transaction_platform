package dev.floresm.transactions.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionHistoryServiceTest {

    @Test
    void cursorRoundTripsLosslessly() {
        var position = new TransactionHistoryService.Position(Instant.parse("2026-10-22T12:00:00.123456Z"), UUID.randomUUID());
        assertThat(TransactionHistoryService.Position.decode(position.encode())).isEqualTo(position);
    }

    @Test
    void garbageCursorIsRejected() {
        assertThatThrownBy(() -> TransactionHistoryService.Position.decode("not-a-cursor"))
                .isInstanceOf(TransactionHistoryService.InvalidCursorException.class);
    }
}
