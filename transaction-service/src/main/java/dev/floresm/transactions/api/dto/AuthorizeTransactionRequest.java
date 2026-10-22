package dev.floresm.transactions.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AuthorizeTransactionRequest(
        @NotNull UUID cardId,
        @NotNull @Valid MoneyDto amount,
        @NotNull @Valid MerchantDto merchant
) {
}
