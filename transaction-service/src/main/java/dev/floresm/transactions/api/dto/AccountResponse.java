package dev.floresm.transactions.api.dto;

import dev.floresm.transactions.domain.account.Account;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String holderName,
        MoneyDto creditLimit,
        MoneyDto availableCredit,
        Instant createdAt
) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(a.id(), a.holderName(), MoneyDto.from(a.creditLimit()),
                MoneyDto.from(a.availableCredit()), a.createdAt());
    }
}
