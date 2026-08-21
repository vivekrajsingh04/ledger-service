package com.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.AbstractPostgresTest;
import com.ledger.TestLedgerFixtures;
import com.ledger.repo.PostingRepository;
import com.ledger.service.RetryingLedgerFacade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Measures throughput at a controlled conflict rate. Produces the numbers behind
 * the README's optimistic-vs-pessimistic chart.
 *
 * <p>"Conflict rate" here is the probability that a given transfer targets the
 * single shared hot account rather than one of many cold accounts. At 0% every
 * write touches a distinct row and nothing ever contends; at 100% every write
 * fights for the same row. Sweeping it is what turns "optimistic is faster" into
 * a statement with a crossover point.
 *
 * <p>Run with {@code -Dledger.bench=true}; skipped by default, because a
 * benchmark that runs on every CI push is a benchmark whose numbers nobody
 * trusts and whose runtime everybody resents.
 */
@DisplayName("contention benchmark")
class ContentionBenchmarkTest extends AbstractPostgresTest {

    private static final int THREADS = 32;
    private static final int OPS_PER_THREAD = 60;
    private static final int COLD_ACCOUNTS = 64;

    @Autowired
    RetryingLedgerFacade ledger;
    @Autowired
    TestLedgerFixtures fixtures;
    @Autowired
    PostingRepository postings;

    @ParameterizedTest(name = "conflict rate {0}%")
    @ValueSource(ints = {0, 10, 25, 40, 60, 100})
    void measureThroughputAtConflictRate(int conflictPercent) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("ledger.bench"),
                "enable with -Dledger.bench=true");

        String strategy = System.getProperty("ledger.concurrency", "optimistic");

        UUID hot = fixtures.createAsset("bench-hot-" + conflictPercent);
        List<UUID> cold = new ArrayList<>();
        for (int i = 0; i < COLD_ACCOUNTS; i++) {
            cold.add(fixtures.createAsset("bench-cold-" + conflictPercent + "-" + i));
        }
        UUID funding = fixtures.createLiability("bench-funding-" + conflictPercent);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Long> latenciesNanos = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < OPS_PER_THREAD; i++) {
                            UUID target = ThreadLocalRandom.current().nextInt(100) < conflictPercent
                                    ? hot
                                    : cold.get(ThreadLocalRandom.current().nextInt(COLD_ACCOUNTS));
                            long began = System.nanoTime();
                            try {
                                ledger.createEntry(
                                        "bench-" + conflictPercent + "-" + threadIndex + "-" + i,
                                        TestLedgerFixtures.transfer(funding, target, 10L));
                                latenciesNanos.add(System.nanoTime() - began);
                                completed.incrementAndGet();
                            } catch (Exception e) {
                                failed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            long began = System.nanoTime();
            start.countDown();
            assertThat(done.await(10, TimeUnit.MINUTES)).isTrue();
            long elapsedNanos = System.nanoTime() - began;

            double seconds = elapsedNanos / 1e9;
            double throughput = completed.get() / seconds;

            List<Long> sorted = new ArrayList<>(latenciesNanos);
            java.util.Collections.sort(sorted);

            String row = String.format(
                    "%s,%d,%d,%d,%.1f,%.2f,%.2f,%.2f",
                    strategy, conflictPercent, completed.get(), failed.get(), throughput,
                    percentileMillis(sorted, 0.50), percentileMillis(sorted, 0.95),
                    percentileMillis(sorted, 0.99));

            System.out.println("BENCH," + row);
            appendResult(row);

            // Correctness is not negotiable even in a benchmark: a fast run that
            // lost money is a failed run.
            assertThat(postings.sumOfAllPostings()).isZero();
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private static double percentileMillis(List<Long> sortedNanos, double q) {
        if (sortedNanos.isEmpty()) {
            return Double.NaN;
        }
        int index = Math.min(sortedNanos.size() - 1,
                (int) Math.round(q * (sortedNanos.size() - 1)));
        return sortedNanos.get(index) / 1e6;
    }

    private static void appendResult(String row) {
        try {
            Path out = Path.of("target", "contention-benchmark.csv");
            Files.createDirectories(out.getParent());
            if (Files.notExists(out)) {
                Files.writeString(out,
                        "strategy,conflict_percent,completed,failed,throughput_per_sec,"
                                + "p50_ms,p95_ms,p99_ms\n");
            }
            Files.writeString(out, row + "\n", java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("could not write benchmark row: " + e.getMessage());
        }
    }
}
