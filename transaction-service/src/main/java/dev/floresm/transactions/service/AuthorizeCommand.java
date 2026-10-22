package dev.floresm.transactions.service;

import dev.floresm.transactions.domain.money.Money;
import dev.floresm.transactions.domain.transaction.Merchant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Input of the "authorize a transaction" use case, already converted from JSON into domain types.
 * Using a <b>command object</b> instead of five method parameters keeps the service signature
 * stable and makes the call site readable.
 */
public record AuthorizeCommand(UUID cardId, Money amount, Merchant merchant, String idempotencyKey) {

    public AuthorizeCommand {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    /**
     * A SHA-256 hash of the request's business content. Stored next to the idempotency key so a
     * retry can be checked: same key + same fingerprint = genuine retry; same key + different
     * fingerprint = client bug.
     *
     * <p>Each field is <b>length-prefixed</b> ("3:abc") before hashing. Simply joining with "|" would
     * be ambiguous: merchant "A|B" + country "C" and merchant "A" + "B|C" could produce the same
     * string, and therefore the same hash, for different requests.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[]{
                cardId.toString(),
                Long.toString(amount.minorUnits()),
                amount.currency().getCurrencyCode(),
                merchant.name(),
                merchant.categoryCode(),
                merchant.country()}) {
            canonical.append(field.length()).append(':').append(field);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to ship SHA-256, so this can only be a broken runtime.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
