package com.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.AbstractPostgresTest;
import com.ledger.TestLedgerFixtures;
import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.service.QueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP-level contract: status codes, problem+json, headers, pagination. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("HTTP API contract")
class LedgerApiTest extends AbstractPostgresTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    TestLedgerFixtures fixtures;
    @Autowired
    MeterRegistry meters;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    @Test
    @DisplayName("POST /v1/entries returns 201 then 200 on replay")
    void createThenReplay() {
        UUID from = fixtures.createLiability("api-from");
        UUID to = fixtures.createAsset("api-to");
        CreateEntryRequest body = TestLedgerFixtures.transfer(from, to, 12_345L);
        String key = "api-" + UUID.randomUUID();

        ResponseEntity<String> first = rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(body, headers(key)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getLocation()).isNotNull();

        ResponseEntity<String> replay = rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(body, headers(key)), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
    }

    @Test
    @DisplayName("a missing Idempotency-Key is a 400 problem+json, not a 500")
    void missingIdempotencyKeyIsAProblem() {
        UUID from = fixtures.createLiability("nokey-from");
        UUID to = fixtures.createAsset("nokey-to");

        ResponseEntity<String> response = rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(TestLedgerFixtures.transfer(from, to, 10L), headers(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType())
                .as("RFC 7807 media type")
                .hasToString("application/problem+json");
        assertThat(response.getBody()).contains("Idempotency-Key").contains("\"type\"");
    }

    @Test
    @DisplayName("an unbalanced entry is 422 and names the imbalance per currency")
    void unbalancedEntryIsUnprocessable() {
        UUID a = fixtures.createAsset("unbal-a");
        UUID b = fixtures.createLiability("unbal-b");

        ResponseEntity<String> response = rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(TestLedgerFixtures.unbalanced(a, b, 100L, 60L),
                        headers("unbal-" + UUID.randomUUID())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .contains("imbalanceByCurrency")
                .contains("INR")
                .contains("40");
    }

    @Test
    @DisplayName("key reuse with a different body is 409")
    void keyReuseIsConflict() {
        UUID from = fixtures.createLiability("conf-from");
        UUID to = fixtures.createAsset("conf-to");
        String key = "conf-" + UUID.randomUUID();

        rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(TestLedgerFixtures.transfer(from, to, 100L), headers(key)),
                String.class);

        ResponseEntity<String> conflict = rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(TestLedgerFixtures.transfer(from, to, 500L), headers(key)),
                String.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("idempotency-key-reuse");
    }

    @Test
    @DisplayName("every response carries a trace id")
    void responsesCarryATraceId() {
        UUID account = fixtures.createAsset("trace");
        ResponseEntity<String> response = rest.getForEntity(
                url("/v1/accounts/" + account + "/balance"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
    }

    @Test
    @DisplayName("an inbound trace id is propagated rather than replaced")
    void inboundTraceIdIsHonoured() {
        UUID account = fixtures.createAsset("trace2");
        HttpHeaders h = new HttpHeaders();
        h.set("X-Trace-Id", "upstream-trace-123");

        ResponseEntity<String> response = rest.exchange(
                url("/v1/accounts/" + account + "/balance"), HttpMethod.GET,
                new HttpEntity<>(h), String.class);

        assertThat(response.getHeaders().getFirst("X-Trace-Id"))
                .isEqualTo("upstream-trace-123");
    }

    @Test
    @DisplayName("statement pages with a cursor, and the pages do not overlap")
    void statementPaginatesWithACursor() {
        UUID from = fixtures.createLiability("page-from");
        UUID to = fixtures.createAsset("page-to");
        for (int i = 0; i < 25; i++) {
            rest.exchange(url("/v1/entries"), HttpMethod.POST,
                    new HttpEntity<>(TestLedgerFixtures.transfer(from, to, 10L + i),
                            headers("page-" + i + "-" + UUID.randomUUID())),
                    String.class);
        }

        ResponseEntity<StatementBody> page1 = rest.getForEntity(
                url("/v1/accounts/" + to + "/statement?limit=10"), StatementBody.class);
        assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page1.getBody().items()).hasSize(10);
        assertThat(page1.getBody().hasMore()).isTrue();
        assertThat(page1.getBody().nextCursor()).isNotBlank();

        ResponseEntity<StatementBody> page2 = rest.getForEntity(
                url("/v1/accounts/" + to + "/statement?limit=10&cursor="
                        + page1.getBody().nextCursor()), StatementBody.class);
        assertThat(page2.getBody().items()).hasSize(10);

        List<String> ids1 = page1.getBody().items().stream().map(m -> m.get("id").toString()).toList();
        List<String> ids2 = page2.getBody().items().stream().map(m -> m.get("id").toString()).toList();
        assertThat(ids1).doesNotContainAnyElementsOf(ids2);
    }

    @Test
    @DisplayName("a garbage cursor is a 400, not a 500")
    void invalidCursorIsARequestError() {
        UUID account = fixtures.createAsset("badcursor");
        ResponseEntity<String> response = rest.getForEntity(
                url("/v1/accounts/" + account + "/statement?cursor=!!!not-base64!!!"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("cursor");
    }

    @Test
    @DisplayName("limit above the maximum is rejected")
    void oversizedLimitIsRejected() {
        UUID account = fixtures.createAsset("biglimit");
        ResponseEntity<String> response = rest.getForEntity(
                url("/v1/accounts/" + account + "/statement?limit="
                        + (QueryService.MAX_PAGE_SIZE + 1)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a decimal amount is rejected -- money is integer minor units")
    void decimalAmountsAreRejected() {
        UUID from = fixtures.createLiability("dec-from");
        UUID to = fixtures.createAsset("dec-to");

        String body = """
                {"description":"decimal","postings":[
                  {"accountId":"%s","amountMinor":10.55,"currency":"INR"},
                  {"accountId":"%s","amountMinor":-10.55,"currency":"INR"}]}
                """.formatted(to, from);

        ResponseEntity<String> response = rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(body, headers("dec-" + UUID.randomUUID())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("balance?as_of reconstructs a past balance")
    void asOfBalanceIsServedOverHttp() {
        UUID from = fixtures.createLiability("http-asof-from");
        UUID to = fixtures.createAsset("http-asof-to");

        rest.exchange(url("/v1/entries"), HttpMethod.POST,
                new HttpEntity<>(TestLedgerFixtures.transfer(from, to, 1_000L),
                        headers("asof-http-" + UUID.randomUUID())), String.class);

        ResponseEntity<String> past = rest.getForEntity(
                url("/v1/accounts/" + to + "/balance?as_of=2000-01-01T00:00:00Z"),
                String.class);
        ResponseEntity<String> now = rest.getForEntity(
                url("/v1/accounts/" + to + "/balance"), String.class);

        assertThat(past.getBody()).contains("\"balanceMinor\":0")
                .contains("\"recomputed\":true");
        assertThat(now.getBody()).contains("\"balanceMinor\":1000")
                .contains("\"recomputed\":false");
    }

    @Test
    @DisplayName("the OpenAPI document is generated and served")
    void openApiDocumentIsAvailable() {
        ResponseEntity<String> spec = rest.getForEntity(url("/v3/api-docs"), String.class);
        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spec.getBody())
                .contains("/v1/entries")
                .contains("/v1/accounts/{id}/balance")
                .contains("Idempotency-Key");
    }

    @Test
    @DisplayName("the gauges the CI drift gate reads are registered under the expected names")
    void driftGaugesAreRegisteredForScraping() {
        // What this protects: CI fails the build if ledger_reconciliation_drift_total
        // is non-zero after the k6 run, and the k6 teardown parses that name out of
        // the scrape body. If a gauge were renamed, the gate would silently pass on
        // a metric that no longer exists rather than on a healthy ledger.
        //
        // Asserted against the MeterRegistry rather than over HTTP. Endpoint
        // exposure is verified end-to-end by the compose smoke job, which runs the
        // packaged application and whose k6 teardown reads /actuator/prometheus for
        // real -- a stronger check than this test slice can make, and one that does
        // not depend on how actuator auto-configuration resolves inside a
        // @SpringBootTest context.
        assertThat(meters.find("ledger.reconciliation.drift.total").gauge())
                .as("drift gauge -> ledger_reconciliation_drift_total")
                .isNotNull();
        assertThat(meters.find("ledger.reconciliation.drift.accounts").gauge())
                .as("drifting-account count gauge")
                .isNotNull();
        assertThat(meters.find("ledger.postings.global.sum").gauge())
                .as("global posting sum -> ledger_postings_global_sum")
                .isNotNull();

        // Every metric carries the active strategy, which is what makes the
        // optimistic-vs-pessimistic comparison one query instead of two runs.
        assertThat(meters.find("ledger.reconciliation.drift.total").gauge().getId().getTag("concurrency"))
                .isNotNull();
    }

    /** Loose shape so the test does not depend on DTO field order. */
    record StatementBody(List<java.util.Map<String, Object>> items, String nextCursor,
                         boolean hasMore) {
    }
}
