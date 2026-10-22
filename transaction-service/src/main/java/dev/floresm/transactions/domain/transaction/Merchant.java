package dev.floresm.transactions.domain.transaction;

import java.util.Objects;

/**
 * Where the card is being used. Value object.
 *
 * @param name         merchant display name, e.g. "OXXO Reforma"
 * @param categoryCode ISO 18245 Merchant Category Code (MCC), 4 digits. e.g. 5411 = grocery stores,
 *                     7995 = gambling. MCC is a classic fraud-risk signal.
 * @param country      ISO 3166-1 alpha-2 country code, e.g. "MX"
 */
public record Merchant(String name, String categoryCode, String country) {

    public Merchant {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(categoryCode, "categoryCode");
        Objects.requireNonNull(country, "country");
        if (name.isBlank()) {
            throw new IllegalArgumentException("merchant name must not be blank");
        }
        if (!categoryCode.matches("\\d{4}")) {
            throw new IllegalArgumentException("categoryCode must be 4 digits");
        }
        if (!country.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("country must be ISO 3166-1 alpha-2");
        }
    }
}
