package dev.floresm.transactions.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: the real application (HTTP server, Spring, Flyway, SQL) against a <b>real
 * PostgreSQL 16</b> started in Docker by Testcontainers. Things mocks cannot prove are proven here:
 * the migration is valid, the SQL is correct, the UNIQUE constraint and the row lock behave as the
 * design claims.
 *
 * <p>Why not an in-memory database such as H2? Because H2 is not PostgreSQL: different SQL dialect,
 * different locking. A test that passes on H2 can fail in production. Test against what you run.
 *
 * <p>{@code @ServiceConnection} makes Spring Boot point the datasource at the container automatically.
 * Runs in Maven's "verify" phase (failsafe plugin) because the class name ends with IT.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;

    // ---------- helpers ----------

    private JsonNode post(String path, String body, HttpHeaders headers, HttpStatus expected) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(expected);
        return read(response.getBody());
    }

    private JsonNode get(String path, HttpStatus expected) {
        ResponseEntity<String> response = http.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(expected);
        return read(response.getBody());
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String openAccount(String limit) {
        return post("/api/v1/accounts", """
                {"holderName": "Ada Lovelace", "creditLimit": {"amount": "%s", "currency": "USD"}}
                """.formatted(limit), new HttpHeaders(), HttpStatus.CREATED).get("id").asText();
    }

    private String issueCard(String accountId) {
        return post("/api/v1/accounts/" + accountId + "/cards", "", new HttpHeaders(), HttpStatus.CREATED).get("id").asText();
    }

    private static String purchase(String cardId, String amount) {
        return """
                {"cardId": "%s", "amount": {"amount": "%s", "currency": "USD"},
                 "merchant": {"name": "OXXO Reforma", "categoryCode": "5411", "country": "MX"}}
                """.formatted(cardId, amount);
    }

    private static HttpHeaders idempotencyKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return headers;
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---------- tests ----------

    @Test
    void fullFlowApprovesReservesCreditAndAppearsInHistory() {
        String accountId = openAccount("100.00");
        String cardId = issueCard(accountId);

        JsonNode tx = post("/api/v1/transactions", purchase(cardId, "30.25"), idempotencyKey(newKey()), HttpStatus.CREATED);

        assertThat(tx.get("status").asText()).isEqualTo("APPROVED");
        assertThat(tx.at("/amount/amount").asText()).isEqualTo("30.25");
        assertThat(get("/api/v1/accounts/" + accountId, HttpStatus.OK).at("/availableCredit/amount").asText())
                .isEqualTo("69.75");
        assertThat(get("/api/v1/cards/" + cardId + "/transactions", HttpStatus.OK).get("items")).hasSize(1);
    }

    @Test
    void retryWithSameIdempotencyKeyDoesNotChargeTwice() {
        String accountId = openAccount("100.00");
        String cardId = issueCard(accountId);
        String key = newKey();

        JsonNode first = post("/api/v1/transactions", purchase(cardId, "40.00"), idempotencyKey(key), HttpStatus.CREATED);
        JsonNode retry = post("/api/v1/transactions", purchase(cardId, "40.00"), idempotencyKey(key), HttpStatus.CREATED);

        assertThat(retry.get("id")).isEqualTo(first.get("id"));
        assertThat(get("/api/v1/accounts/" + accountId, HttpStatus.OK).at("/availableCredit/amount").asText())
                .as("charged once, not twice").isEqualTo("60.00");
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyIs422() {
        String cardId = issueCard(openAccount("100.00"));
        String key = newKey();
        post("/api/v1/transactions", purchase(cardId, "10.00"), idempotencyKey(key), HttpStatus.CREATED);

        JsonNode problem = post("/api/v1/transactions", purchase(cardId, "99.00"), idempotencyKey(key), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(problem.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void blockedCardIsDeclinedButStillRecorded() {
        String cardId = issueCard(openAccount("100.00"));
        post("/api/v1/cards/" + cardId + "/block", "", new HttpHeaders(), HttpStatus.OK);

        JsonNode tx = post("/api/v1/transactions", purchase(cardId, "10.00"), idempotencyKey(newKey()), HttpStatus.CREATED);

        assertThat(tx.get("status").asText()).isEqualTo("DECLINED");
        assertThat(tx.get("declineReason").asText()).isEqualTo("CARD_NOT_ACTIVE");
    }

    /**
     * The race-condition test. 10 threads each try to spend 20.00 at the same moment against a
     * 100.00 limit. Without the row lock, several threads would read "100.00 available" before any
     * of them writes, and we would approve more than 100.00. With the lock: exactly 5 approvals.
     */
    @Test
    void concurrentPurchasesNeverOverspendTheCreditLimit() throws Exception {
        String accountId = openAccount("100.00");
        String cardId = issueCard(accountId);
        int threads = 10;
        CountDownLatch startGun = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<String> attempt = () -> {
                    startGun.await(); // all threads wait here, then fire together
                    return post("/api/v1/transactions", purchase(cardId, "20.00"), idempotencyKey(newKey()), HttpStatus.CREATED)
                            .get("status").asText();
                };
                results.add(pool.submit(attempt));
            }
            startGun.countDown();

            long approved = 0;
            for (Future<String> result : results) {
                if (result.get().equals("APPROVED")) {
                    approved++;
                }
            }
            assertThat(approved).isEqualTo(5);
        }
        assertThat(get("/api/v1/accounts/" + accountId, HttpStatus.OK).at("/availableCredit/amount").asText())
                .isEqualTo("0.00");
    }

    @Test
    void historyIsPaginatedNewestFirstWithoutDuplicatesOrGaps() {
        String cardId = issueCard(openAccount("1000.00"));
        for (int i = 1; i <= 5; i++) {
            post("/api/v1/transactions", purchase(cardId, i + ".00"), idempotencyKey(newKey()), HttpStatus.CREATED);
        }

        Set<String> seen = new HashSet<>();
        List<String> amounts = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/api/v1/cards/" + cardId + "/transactions?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = get(url, HttpStatus.OK);
            page.get("items").forEach(item -> {
                assertThat(seen.add(item.get("id").asText())).as("no duplicates").isTrue();
                amounts.add(item.at("/amount/amount").asText());
            });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3); // 2 + 2 + 1
        assertThat(amounts).containsExactly("5.00", "4.00", "3.00", "2.00", "1.00");
    }

    @Test
    void validationErrorsUseProblemDetailsAndListEveryField() {
        JsonNode problem = post("/api/v1/transactions", """
                {"cardId": null, "amount": {"amount": "-5", "currency": "usd"},
                 "merchant": {"name": "", "categoryCode": "54", "country": "Mexico"}}
                """, idempotencyKey(newKey()), HttpStatus.BAD_REQUEST);

        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("cardId", "amount.amount", "amount.currency",
                "merchant.name", "merchant.categoryCode", "merchant.country");
    }

    @Test
    void missingIdempotencyKeyIs400() {
        String cardId = issueCard(openAccount("100.00"));
        JsonNode problem = post("/api/v1/transactions", purchase(cardId, "1.00"), new HttpHeaders(), HttpStatus.BAD_REQUEST);
        assertThat(problem.get("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void malformedIdempotencyKeyIsReportedByItsHeaderName() {
        String cardId = issueCard(openAccount("100.00"));
        JsonNode problem = post("/api/v1/transactions", purchase(cardId, "1.00"), idempotencyKey("bad key!"), HttpStatus.BAD_REQUEST);
        assertThat(problem.at("/errors/0/field").asText()).isEqualTo("Idempotency-Key");
    }

    @Test
    void tooManyDecimalsIs400NotSilentlyRounded() {
        String cardId = issueCard(openAccount("100.00"));
        JsonNode problem = post("/api/v1/transactions", purchase(cardId, "1.005"), idempotencyKey(newKey()), HttpStatus.BAD_REQUEST);
        assertThat(problem.get("code").asText()).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void unknownResourcesAre404() {
        assertThat(get("/api/v1/cards/" + UUID.randomUUID(), HttpStatus.NOT_FOUND).get("code").asText())
                .isEqualTo("NOT_FOUND");
        get("/api/v1/transactions/" + UUID.randomUUID(), HttpStatus.NOT_FOUND);
    }

    @Test
    void healthAndOpenApiAreExposed() {
        assertThat(get("/actuator/health", HttpStatus.OK).get("status").asText()).isEqualTo("UP");
        assertThat(get("/v3/api-docs", HttpStatus.OK).get("paths").has("/api/v1/transactions")).isTrue();
    }
}
