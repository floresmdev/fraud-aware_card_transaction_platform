package dev.floresm.transactions.api;

import dev.floresm.transactions.api.dto.AuthorizeTransactionRequest;
import dev.floresm.transactions.api.dto.TransactionResponse;
import dev.floresm.transactions.service.AuthorizationResult;
import dev.floresm.transactions.service.AuthorizationService;
import dev.floresm.transactions.service.AuthorizeCommand;
import dev.floresm.transactions.service.TransactionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions")
public class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final AuthorizationService authorizationService;
    private final TransactionHistoryService historyService;

    public TransactionController(AuthorizationService authorizationService,
                                 TransactionHistoryService historyService) {
        this.authorizationService = authorizationService;
        this.historyService = historyService;
    }

    /**
     * Authorize a card transaction.
     *
     * <p><b>A decline is not an HTTP error.</b> The request was valid and fully processed; the
     * business answer happens to be "no". So a decline returns 201 with {@code status: DECLINED}.
     * 4xx means "your request is wrong", 5xx means "we failed". Mixing business outcomes into HTTP
     * error codes makes clients retry things they should not, and pollutes error-rate alarms.
     *
     * <p><b>POST is not idempotent by HTTP semantics</b> (unlike GET, PUT, DELETE), so we make it
     * idempotent with a required {@code Idempotency-Key} header (the pattern Stripe and Adyen use).
     * A retry with the same key gets the original response and status code, plus
     * {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    @Operation(summary = "Authorize a card transaction (idempotent)")
    public ResponseEntity<TransactionResponse> authorize(
            @Parameter(description = "Unique per logical request; reuse it only when retrying. A UUID is recommended.")
            @RequestHeader(IDEMPOTENCY_KEY)
            @Pattern(regexp = "[A-Za-z0-9_\\-]{8,255}", message = "must be 8-255 characters of [A-Za-z0-9_-]")
            String idempotencyKey,
            @Valid @RequestBody AuthorizeTransactionRequest request) {

        AuthorizationResult result = authorizationService.authorize(new AuthorizeCommand(
                request.cardId(), request.amount().toDomain(), request.merchant().toDomain(), idempotencyKey));

        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/transactions/" + result.transaction().id()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
                .body(TransactionResponse.from(result.transaction()));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get a transaction")
    public TransactionResponse get(@PathVariable UUID transactionId) {
        return TransactionResponse.from(historyService.getTransaction(transactionId));
    }
}
