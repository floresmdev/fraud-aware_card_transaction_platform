package dev.floresm.transactions.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code @Valid} on a nested object tells Bean Validation to validate its fields too. */
public record CreateAccountRequest(
        @NotBlank @Size(max = 200) String holderName,
        @NotNull @Valid MoneyDto creditLimit
) {
}
