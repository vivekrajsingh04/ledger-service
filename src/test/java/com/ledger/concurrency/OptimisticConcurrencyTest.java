package com.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.AbstractPostgresTest;
import com.ledger.TestLedgerFixtures;
import com.ledger.repo.AccountBalanceRepository;
import com.ledger.repo.PostingRepository;
import com.ledger.service.ConcurrencyExhaustedException;
import com.ledger.service.ReconciliationJob;
import com.ledger.service.RetryingLedgerFacade;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The optimistic strategy, tested against what it actually guarantees.
 *
 * <p>Two distinct claims, because they are genuinely different:
 *
 * <ol>
 *   <li><b>At the contention it is for</b> -- many writers spread across many
 *       accounts -- it is exact, with no lost updates.</li>
 *   <li><b>At contention it is not for</b> -- every writer on one row -- it
 *       degrades <em>safely</em>. Some requests fail with an explicit 503, and
 *       the final balance still equals exactly the work that succeeded. Losing
 *       throughput is acceptable; losing money is not.</li>
 * </ol>
 *
 * <p>The second is the more important test. Any implementation can be correct
 * when nothing conflicts; what matters is whether the failure mode is loud and
 * lossless or quiet and lossy.
 */
@DisplayName("optimistic concurrency: exact when suited, safe when not")
@TestPropertySource(properties = "ledger.concurrency=optimistic")
class OptimisticConcurrencyTest extends AbstractPostgresTest {

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
    @DisplayName("exact across many accounts -- the contention optimistic is for")
    // Genuinely spread: every entry touches one of 30 targets and the thread's
    // own funding account, so no single row sees all 2,400 writes.
    void exactUnderSpreadContention() throws Exception {
        int threads = 60;
        int perThread = 40;
        int accounts = 30;

        List<UUID> targets = IntStream.range(0, accounts)
                .mapToObj(i -> fixtures.createAsset("spread-target-" + i))
                .toList();
        // One funding account per thread, not one shared account. A single
        // funding source would appear on the credit side of every entry and be a
        // hot row in its own right -- the test would then measure hot-row
        // contention while claiming to measure spread contention, which is how
        // the first version of this test failed with 41 of 60 threads giving up.
        List<UUID> funding = IntStream.range(0, threads)
                .mapToObj(i -> fixtures.createLiability("spread-funding-" + i))
                .toList();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger[] perAccount = new AtomicInteger[accounts];
        for (int i = 0; i < accounts; i++) {
            perAccount[i] = new AtomicInteger();
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            int idx = ThreadLocalRandom.current().nextInt(accounts);
                            ledger.createEntry("spread-" + threadIndex + "-" + i,
                                    TestLedgerFixtures.transfer(
                                            funding.get(threadIndex), targets.get(idx), 100L));
                            perAccount[idx].incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
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

        assertThat(failed.get())
                .as("optimistic should not need to give up at this contention")
                .isZero();

        for (int i = 0; i < accounts; i++) {
            long expected = perAccount[i].get() * 100L;
            assertThat(balances.findByAccountId(targets.get(i)).orElseThrow().getBalanceMinor())
                    .as("account %d materialised balance", i)
                    .isEqualTo(expected);
            assertThat(postings.currentBalance(targets.get(i)))
                    .as("account %d recomputed balance", i)
                    .isEqualTo(expected);
        }

        assertThat(postings.sumOfAllPostings()).isZero();
        assertThat(reconciliation.reconcile().isClean()).isTrue();
    }

    @Test
    @DisplayName("one hot row: sheds load with 503s, and loses nothing it accepted")
    void degradesSafelyUnderPathologicalContention() throws Exception {
        int threads = 60;
        int perThread = 30;

        UUID hot = fixtures.createAsset("optimistic-hot");
        List<UUID> sources = IntStream.range(0, threads)
                .mapToObj(i -> fixtures.createLiability("opt-src-" + i))
                .toList();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger shed = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final UUID source = sources.get(t);
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            try {
                                ledger.createEntry("opt-hot-" + threadIndex + "-" + i,
                                        TestLedgerFixtures.transfer(source, hot, 100L));
                                committed.incrementAndGet();
                            } catch (ConcurrencyExhaustedException e) {
                                // The documented, actionable failure: a 503 with
                                // Retry-After. Load shed, nothing written.
                                shed.incrementAndGet();
                            } catch (Exception e) {
                                unexpected.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.MINUTES)).isTrue();
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(unexpected.get())
                .as("every failure must be an explicit ConcurrencyExhaustedException, "
                        + "never an unhandled error")
                .isZero();
        assertThat(committed.get()).as("some work must get through").isPositive();

        // The claim that matters: the balance equals exactly what committed.
        // Not approximately -- exactly. A shed request must leave no trace, and a
        // committed one must never be lost.
        long expected = committed.get() * 100L;
        assertThat(balances.findByAccountId(hot).orElseThrow().getBalanceMinor())
                .as("materialised balance must equal exactly the committed work "
                        + "(%d committed, %d shed)", committed.get(), shed.get())
                .isEqualTo(expected);
        assertThat(postings.currentBalance(hot))
                .as("recomputed balance must agree")
                .isEqualTo(expected);

        assertThat(postings.sumOfAllPostings())
                .as("double-entry invariant survives load shedding")
                .isZero();
        assertThat(reconciliation.reconcile().isClean()).isTrue();

        System.out.printf("optimistic under one-row contention: %d committed, %d shed (503)%n",
                committed.get(), shed.get());
    }
}
