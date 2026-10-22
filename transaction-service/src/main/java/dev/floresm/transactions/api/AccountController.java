package dev.floresm.transactions.api;

import dev.floresm.transactions.api.dto.AccountResponse;
import dev.floresm.transactions.api.dto.CardResponse;
import dev.floresm.transactions.api.dto.CreateAccountRequest;
import dev.floresm.transactions.domain.account.Account;
import dev.floresm.transactions.domain.card.Card;
import dev.floresm.transactions.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * REST resources for accounts.
 *
 * <p>The controller is deliberately thin: translate HTTP to a service call and the result back to
 * HTTP. No business logic here. REST conventions used:
 * <ul>
 *   <li>Nouns in URLs, HTTP verbs for actions; version prefix {@code /api/v1} so v2 can coexist.</li>
 *   <li>{@code POST} that creates returns <b>201 Created</b> with a {@code Location} header pointing
 *       to the new resource.</li>
 *   <li>Cards are a sub-resource: {@code POST /accounts/{id}/cards} issues a card for that account.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Open a credit account")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.openAccount(request.holderName(), request.creditLimit().toDomain());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account, including its available credit")
    public AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(accountService.getAccount(accountId));
    }

    @PostMapping("/{accountId}/cards")
    @Operation(summary = "Issue a new card for the account")
    public ResponseEntity<CardResponse> issueCard(@PathVariable UUID accountId) {
        Card card = accountService.issueCard(accountId);
        return ResponseEntity.created(URI.create("/api/v1/cards/" + card.id()))
                .body(CardResponse.from(card));
    }
}
