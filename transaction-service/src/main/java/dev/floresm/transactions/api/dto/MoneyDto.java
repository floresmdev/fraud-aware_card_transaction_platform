package dev.floresm.transactions.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.floresm.transactions.domain.money.Money;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * JSON shape of money: {@code {"amount": "12.34", "currency": "USD"}}.
 *
 * <p>The amount is written as a JSON <b>string</b> ({@code @JsonFormat(shape = STRING)}). Many
 * JSON clients (JavaScript in particular) parse numbers as 64-bit floats, which would reintroduce
 * the exact rounding problem we avoid on the server. Input accepts both "12.34" and 12.34, and
 * because the Java type is {@link BigDecimal}, Jackson parses the digits exactly.
 *
 * <p>DTOs (Data Transfer Objects) are the API contract and are kept separate from domain classes,
 * so internal refactors do not break clients and clients cannot set fields they should not
 * (e.g. availableCredit).
 */
public record MoneyDto(
        @NotNull @Positive @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code like USD") String currency
) {

    public static MoneyDto from(Money money) {
        return new MoneyDto(money.toDecimal(), money.currency().getCurrencyCode());
    }

    public Money toDomain() {
        return Money.of(amount, currency);
    }
}
