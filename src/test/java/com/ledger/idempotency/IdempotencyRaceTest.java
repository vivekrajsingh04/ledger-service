package com.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.AbstractPostgresTest;
import com.ledger.TestLedgerFixtures;
import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.EntryResponse;
import com.ledger.api.dto.PostingRequest;
import com.ledger.error.IdempotencyConflictException;
import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.JournalEntryRepository;
import com.ledger.repo.PostingRepository;
import com.ledger.service.IdempotentResult;
import com.ledger.service.RetryingLedgerFacade;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("idempotency under concurrency")
class IdempotencyRaceTest extends AbstractPostgresTest {

    @Autowired
    RetryingLedgerFacade ledger;
    @Autowired
    TestLedgerFixtures fixtures;
    @Autowired
    JournalEntryRepository entries;
    @Autowired
    PostingRepository postings;
    @Autowired
    AccountBalanceRepository balances;

    @Test
    @DisplayName("the same key fired 50x concurrently creates exactly one entry")
    void sameKeyFiredFiftyTimesConcurrentlyCreatesOneEntry() throws Exception {
        UUID from = fixtures.createLiability("race-from");
        UUID to = fixtures.createAsset("race-to");
        CreateEntryRequest request = TestLedgerFixtures.transfer(from, to, 25_000L);

        String key = "race-key-" + UUID.randomUUID();
        int attempts = 50;

        Set<UUID> returnedEntryIds = ConcurrentHashMap.newKeySet();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        IdempotentResult<EntryResponse> result =
                                ledger.createEntry(key, request);
                        returnedEntryIds.add(result.value().id());
                        if (result.replayed()) {
                            replayed.incrementAndGet();
                        } else {
                            created.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(2, TimeUnit.MINUTES)).isTrue();
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(errors.get()).as("no caller should see an error").isZero();

        // The claim: exactly one entry exists.
        assertThat(entries.findByIdempotencyKey(key)).isPresent();
        UUID entryId = entries.findByIdempotencyKey(key).orElseThrow().getId();
        assertThat(postings.findByEntryIdOrderByAmountMinorDesc(entryId)).hasSize(2);

        // Every one of the 50 callers got the *same* entry back -- a caller that
        // received a different id would have been told a lie even if the database
        // ended up with one row.
        assertThat(returnedEntryIds)
                .as("all 50 callers must observe the same entry")
                .containsExactly(entryId);

        assertThat(created.get()).as("exactly one caller created it").isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(attempts - 1);

        // And the money moved exactly once.
        assertThat(balances.findByAccountId(to).orElseThrow().getBalanceMinor())
                .isEqualTo(25_000L);
        assertThat(postings.currentBalance(to)).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("replaying a key returns the original response byte-for-byte")
    void replayReturnsTheStoredResponse() {
        UUID from = fixtures.createLiability("replay-from");
        UUID to = fixtures.createAsset("replay-to");
        CreateEntryRequest request = TestLedgerFixtures.transfer(from, to, 700L);
        String key = "replay-" + UUID.randomUUID();

        IdempotentResult<EntryResponse> first = ledger.createEntry(key, request);
        IdempotentResult<EntryResponse> second = ledger.createEntry(key, request);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).as("a replay is a success, not an error").isTrue();
        assertThat(second.value()).isEqualTo(first.value());
        assertThat(postings.currentBalance(to)).isEqualTo(700L);
    }

    @Test
    @DisplayName("posting order does not make a retry look like a new request")
    void reorderedPostingsHashTheSame() {
        UUID from = fixtures.createLiability("order-from");
        UUID to = fixtures.createAsset("order-to");
        String key = "order-" + UUID.randomUUID();

        CreateEntryRequest original = new CreateEntryRequest("t",
                List.of(new PostingRequest(to, 500L, "INR"),
                        new PostingRequest(from, -500L, "INR")));
        // Same request, postings serialised the other way round. A client's JSON
        // library is free to do this; it is still a retry, not a new request.
        CreateEntryRequest reordered = new CreateEntryRequest("t",
                List.of(new PostingRequest(from, -500L, "INR"),
                        new PostingRequest(to, 500L, "INR")));

        IdempotentResult<EntryResponse> first = ledger.createEntry(key, original);
        IdempotentResult<EntryResponse> second = ledger.createEntry(key, reordered);

        assertThat(second.replayed()).isTrue();
        assertThat(second.value().id()).isEqualTo(first.value().id());
    }

    @Test
    @DisplayName("the same key with a DIFFERENT body is a 409, not a silent replay")
    void keyReuseWithDifferentBodyConflicts() {
        UUID from = fixtures.createLiability("conflict-from");
        UUID to = fixtures.createAsset("conflict-to");
        String key = "conflict-" + UUID.randomUUID();

        ledger.createEntry(key, TestLedgerFixtures.transfer(from, to, 100L));

        // This is the case almost everyone forgets. Returning the original
        // response here would silently swallow a real client bug: they think they
        // moved 999, we moved 100, and nothing anywhere says so.
        assertThatThrownBy(() ->
                ledger.createEntry(key, TestLedgerFixtures.transfer(from, to, 999L)))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining(key);

        assertThat(postings.currentBalance(to))
                .as("the conflicting request must not have moved anything")
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("a failed request does not burn its idempotency key")
    void failedRequestReleasesTheKey() {
        UUID from = fixtures.createLiability("burn-from");
        UUID to = fixtures.createAsset("burn-to");
        String key = "burn-" + UUID.randomUUID();

        // Unbalanced -> rejected, transaction rolls back, so the claim rolls back.
        assertThatThrownBy(() ->
                ledger.createEntry(key, TestLedgerFixtures.unbalanced(to, from, 100L, 50L)))
                .isInstanceOf(RuntimeException.class);

        // The client fixes the body and retries with the same key. That must work:
        // a key consumed by a request that never happened is a key the client can
        // never use, and they have no way to know.
        IdempotentResult<EntryResponse> retried =
                ledger.createEntry(key, TestLedgerFixtures.transfer(from, to, 100L));

        assertThat(retried.replayed()).isFalse();
        assertThat(postings.currentBalance(to)).isEqualTo(100L);
    }
}
