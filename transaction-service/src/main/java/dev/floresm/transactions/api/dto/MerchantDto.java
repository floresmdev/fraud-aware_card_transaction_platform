package dev.floresm.transactions.api.dto;

import dev.floresm.transactions.domain.transaction.Merchant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MerchantDto(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Pattern(regexp = "\\d{4}", message = "must be a 4-digit MCC") String categoryCode,
        @NotNull @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code like MX") String country
) {

    public static MerchantDto from(Merchant m) {
        return new MerchantDto(m.name(), m.categoryCode(), m.country());
    }

    public Merchant toDomain() {
        return new Merchant(name.strip(), categoryCode, country);
    }
}
