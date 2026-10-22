package dev.floresm.transactions.domain.card;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Test
    void issuedCardIsActiveAndExpiresFourYearsLater() {
        Card card = Card.issue(UUID.randomUUID(), LocalDate.of(2026, 10, 22), NOW);
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.expiry()).isEqualTo(YearMonth.of(2030, 10));
        assertThat(card.last4()).matches("\\d{4}");
    }

    @Test
    void cardIsValidThroughTheLastDayOfItsExpiryMonth() {
        Card card = new Card(UUID.randomUUID(), UUID.randomUUID(), "1234", YearMonth.of(2026, 10), CardStatus.ACTIVE, NOW);
        // Boundary tests: the bugs live at the edges (off-by-one).
        assertThat(card.isExpiredOn(LocalDate.of(2026, 10, 31))).isFalse();
        assertThat(card.isExpiredOn(LocalDate.of(2026, 11, 1))).isTrue();
    }

    @Test
    void blockingIsIdempotent() {
        Card card = Card.issue(UUID.randomUUID(), LocalDate.of(2026, 10, 22), NOW);
        Card blocked = card.block();
        assertThat(blocked.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(blocked.block()).isSameAs(blocked);
    }
}
