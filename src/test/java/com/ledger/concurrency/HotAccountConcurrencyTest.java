package com.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.AbstractPostgresTest;
import com.ledger.TestLedgerFixtures;
import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.PostingRepository;
import com.ledger.service.ReconciliationJob;
import com.ledger.service.RetryingLedgerFacade;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 100 threads x 100 transfers against one hot account. Zero lost updates.
 *
 * <p>This is the test the whole concurrency design exists to pass. A lost update
 * here is not a flaky test -- it is money that stopped existing.
 *
 * <p><b>Runs under the pessimistic strategy, deliberately.</b> A single row with
 * 100 concurrent writers is the case optimistic concurrency cannot serve, and
 * that is a property of the technique rather than a defect in this
 * implementation: the winning transaction's {@code UPDATE ... WHERE version = N}
 * holds the row lock until commit, so every other writer blocks, then wakes to
 * find version N+1 and fails. Roughly 99 conflicts per successful commit, which
 * no retry budget converges on -- measured here at 15,216 conflicts with 88
 * requests exhausting 25 attempts apiece.
 *
 * <p>Pessimistic locking queues on the row instead of discovering the conflict
 * afterwards, so it absorbs this exactly. Choosing it for a hot account is the
 * point of shipping both. {@link OptimisticConcurrencyTest} covers the
 * contention level optimistic is actually for, and pins its failure mode.
 */
@DisplayName("100 threads x 100 transfers against one hot account (pessimistic)")
@TestPropertySource(properties = {
        "ledger.concurrency=pessimistic",
        // 100 client threads against a 20-connection pool, where each pessimistic
        // transaction holds its connection for the whole time it waits on the hot
        // row's lock. The threads therefore queue twice -- once for a connection,
        // once for the row -- and a 5s connection-acquisition timeout is not
        // enough for 10,000 transfers serialised through one row: one thread
        // failed with "timed out after 5000ms" purely waiting to be handed a
        // connection.
        //
        // Widened here because the test deliberately drives 5x the concurrency
        // the pool is sized for. In production you size the pool to the
        // concurrency you intend to serve and shed the rest at the edge, rather
        // than letting requests pile up on the pool.
        "spring.datasource.hikari.maximum-pool-size=50",
        "spring.datasource.hikari.connection-timeout=30000",
        "spring.transaction.default-timeout=60"
})
class HotAccountConcurrencyTest extends AbstractPostgresTest {

    private static final int THREADS = 100;
    private static final int TRANSFERS_PER_THREAD = 100;
    private static final long AMOUNT_MINOR = 100L;

    @Autowired
    RetryingLedgerFacade ledger;
    @Autowired
    TestLedgerFixtures fixtures;
    @Autowired
    AccountBalanceRepository balances;
    @Autowired
    PostingRepository postings;
    @Autowired
    ReconciliationJob reconciliation;

    @Test
    @DisplayName("final balance is exactly correct with no lost updates")
    void concurrentTransfersToOneHotAccountAreExact() throws Exception {
        UUID hot = fixtures.createAsset("hot");
        List<UUID> sources = IntStream.range(0, THREADS)
                .mapToObj(i -> fixtures.createLiability("src" + i))
                .toList();

        int expectedTransfers = THREADS * TRANSFERS_PER_THREAD;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // Every thread waits on the same latch so all 100 hit the hot account
        // simultaneously. Without this they trickle in and the test proves nothing.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                final UUID source = sources.get(t);
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                            String key = "hot-" + threadIndex + "-" + i;
                            ledger.createEntry(key,
                                    TestLedgerFixtures.transfer(source, hot, AMOUNT_MINOR));
                            succeeded.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.MINUTES))
                    .as("all threads finished within the timeout")
                    .isTrue();
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(failed.get()).as("no thread failed outright").isZero();
        assertThat(succeeded.get()).isEqualTo(expectedTransfers);

        long expectedBalance = (long) expectedTransfers * AMOUNT_MINOR;

        // 1. The materialised balance is exactly right -- not approximately.
        assertThat(balances.findByAccountId(hot).orElseThrow().getBalanceMinor())
                .as("materialised balance: any shortfall is a lost update")
                .isEqualTo(expectedBalance);

        // 2. The truth, recomputed from postings, agrees.
        assertThat(postings.currentBalance(hot))
                .as("balance recomputed from postings")
                .isEqualTo(expectedBalance);

        // 3. Nothing anywhere in the ledger drifted.
        ReconciliationJob.ReconciliationReport report = reconciliation.reconcile();
        assertThat(report.isClean())
                .as("reconciliation after the storm: %s", report)
                .isTrue();

        // 4. And the global double-entry invariant still holds.
        assertThat(postings.sumOfAllPostings())
                .as("every posting ever written must sum to zero")
                .isZero();
    }

    @Test
    @DisplayName("concurrent bidirectional transfers between the same pair do not deadlock")
    void bidirectionalTransfersDoNotDeadlock() throws Exception {
        UUID a = fixtures.createAsset("pairA");
        UUID b = fixtures.createAsset("pairB");

        int threads = 40;
        int perThread = 25;
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                // Half go A -> B, half go B -> A. Under a naive lock order that
                // follows the request, this is the classic deadlock: one txn holds
                // A waiting for B while another holds B waiting for A.
                final boolean forward = t % 2 == 0;
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            ledger.createEntry("pair-" + threadIndex + "-" + i,
                                    forward ? TestLedgerFixtures.transfer(a, b, 50L)
                                            : TestLedgerFixtures.transfer(b, a, 50L));
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.MINUTES)).isTrue();
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(failures.get())
                .as("deterministic lock ordering must prevent deadlock aborts")
                .isZero();

        // Equal traffic both ways: the two balances must be exact mirrors.
        long balanceA = balances.findByAccountId(a).orElseThrow().getBalanceMinor();
        long balanceB = balances.findByAccountId(b).orElseThrow().getBalanceMinor();
        assertThat(balanceA + balanceB).isZero();
        assertThat(postings.currentBalance(a)).isEqualTo(balanceA);
        assertThat(postings.currentBalance(b)).isEqualTo(balanceB);
    }
}
