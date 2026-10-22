package dev.floresm.transactions.api;

import dev.floresm.transactions.api.dto.CardResponse;
import dev.floresm.transactions.api.dto.TransactionPageResponse;
import dev.floresm.transactions.api.dto.TransactionResponse;
import dev.floresm.transactions.service.AccountService;
import dev.floresm.transactions.service.TransactionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cards")
@Tag(name = "Cards")
public class CardController {

    private final AccountService accountService;
    private final TransactionHistoryService historyService;

    public CardController(AccountService accountService, TransactionHistoryService historyService) {
        this.accountService = accountService;
        this.historyService = historyService;
    }

    @GetMapping("/{cardId}")
    @Operation(summary = "Get a card")
    public CardResponse get(@PathVariable UUID cardId) {
        return CardResponse.from(accountService.getCard(cardId));
    }

    /**
     * Blocking is an <i>action</i>, modelled as {@code POST /cards/{id}/block}. The alternative,
     * {@code PATCH /cards/{id} {"status":"BLOCKED"}}, would let clients set any status, including
     * ones that should only be reachable through business processes. It is idempotent by nature:
     * blocking twice leaves the card blocked.
     */
    @PostMapping("/{cardId}/block")
    @Operation(summary = "Block a card (lost, stolen, suspected fraud)")
    public CardResponse block(@PathVariable UUID cardId) {
        return CardResponse.from(accountService.blockCard(cardId));
    }

    @GetMapping("/{cardId}/transactions")
    @Operation(summary = "Transaction history of a card, newest first, cursor-paginated")
    public TransactionPageResponse history(
            @PathVariable UUID cardId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(TransactionHistoryService.MAX_PAGE_SIZE) int limit,
            @RequestParam(required = false) String cursor) {
        TransactionHistoryService.Page page = historyService.history(cardId, limit, cursor);
        return new TransactionPageResponse(
                page.items().stream().map(TransactionResponse::from).toList(),
                page.nextCursor());
    }
}
