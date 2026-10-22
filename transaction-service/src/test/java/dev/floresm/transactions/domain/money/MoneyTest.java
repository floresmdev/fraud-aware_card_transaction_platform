package dev.floresm.transactions.domain.money;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests: plain Java, no Spring, no database, milliseconds to run.
 * Naming: each test method states the behaviour it proves, so a failure reads like a bug report.
 * Structure inside each test: Arrange / Act / Assert (a.k.a. Given / When / Then).
 */
class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    class Parsing {

        // A parameterized test runs once per row: many cases, one test body.
        @ParameterizedTest
        @CsvSource({
                "12.34, USD, 1234",
                "12.3,  USD, 1230",
                "12,    USD, 1200",
                "0.01,  USD, 1",
                "500,   JPY, 500",
                "12.50, USD, 1250"
        })
        void convertsDecimalAmountsToMinorUnits(String amount, String currency, long expectedMinor) {
            assertThat(Money.of(new BigDecimal(amount), currency).minorUnits()).isEqualTo(expectedMinor);
        }

        @Test
        void rejectsMoreDecimalsThanTheCurrencyAllowsInsteadOfRounding() {
            assertThatThrownBy(() -> Money.of(new BigDecimal("12.345"), USD))
                    .isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> Money.of(new BigDecimal("1.5"), JPY))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void acceptsTrailingZerosBeyondTheMinorUnit() {
            // 12.3400 is numerically 12.34, so it is fine.
            assertThat(Money.of(new BigDecimal("12.3400"), USD).minorUnits()).isEqualTo(1234);
        }

        @Test
        void rejectsUnknownAndUnsupportedCurrencies() {
            assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "ABC")).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "XAU")).isInstanceOf(InvalidAmountException.class);
        }

        @Test
        void rejectsAmountsThatOverflowLong() {
            assertThatThrownBy(() -> Money.of(new BigDecimal("1e30"), USD)).isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void additionIsExactWhereDoubleIsNot() {
            // The classic: 0.1 + 0.2 with doubles is 0.30000000000000004.
            assertThat(0.1 + 0.2).isNotEqualTo(0.3);
            Money sum = Money.of(new BigDecimal("0.10"), USD).plus(Money.of(new BigDecimal("0.20"), USD));
            assertThat(sum).isEqualTo(Money.of(new BigDecimal("0.30"), USD));
        }

        @Test
        void operationsReturnNewInstancesAndNeverMutate() {
            Money ten = new Money(1000, USD);
            Money result = ten.minus(new Money(300, USD));
            assertThat(ten.minorUnits()).isEqualTo(1000);
            assertThat(result.minorUnits()).isEqualTo(700);
        }

        @Test
        void mixingCurrenciesIsAnError() {
            assertThatThrownBy(() -> new Money(100, USD).plus(new Money(100, JPY)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Money(100, USD).compareTo(new Money(100, JPY)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void overflowThrowsInsteadOfWrappingAround() {
            assertThatThrownBy(() -> new Money(Long.MAX_VALUE, USD).plus(new Money(1, USD)))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Test
    void valueObjectsWithTheSameValueAreEqual() {
        assertThat(new Money(1234, USD)).isEqualTo(new Money(1234, USD)).hasSameHashCodeAs(new Money(1234, USD));
    }

    @Test
    void formatsWithTheCurrencyScale() {
        assertThat(new Money(5, USD).toDecimal()).isEqualByComparingTo("0.05");
        assertThat(new Money(5, USD).toDecimal().toPlainString()).isEqualTo("0.05");
        assertThat(new Money(500, JPY).toDecimal().toPlainString()).isEqualTo("500");
    }
}
