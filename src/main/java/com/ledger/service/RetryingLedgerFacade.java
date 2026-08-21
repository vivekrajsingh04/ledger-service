package com.ledger.service;

import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.EntryResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

/**
 * Retries the whole transaction on a concurrency failure.
 *
 * <p><b>This class exists because the retry cannot live inside the service.</b>
 * Once a transaction hits an optimistic lock failure it is marked rollback-only;
 * every subsequent statement in it fails, so retrying in place accomplishes
 * nothing. The retry has to re-enter a fresh transaction from the outside, which
 * means it has to be a separate bean whose method is <em>not</em>
 * {@code @Transactional} calling one whose method is. That is also why this is a
 * distinct class rather than a private method: a self-invocation would bypass the
 * Spring proxy and silently run without a new transaction at all.
 *
 * <p>Backoff is exponential with full jitter. Without jitter, N transactions that
 * conflict at the same instant all sleep the same duration and collide again on
 * every retry -- a synchronised herd that turns contention into a livelock.
 * Randomising across the whole interval spreads them out; this is the "Full
 * Jitter" strategy from AWS's exponential-backoff guidance.
 *
 * <p>Retrying is safe precisely because the operation is idempotent: the entry's
 * idempotency claim rolls back with the failed transaction, so a retry re-claims
 * the same key and produces exactly one entry.
 *
 * <p>The attempt budget is sized for the worst case this project actually tests:
 * 100 threads transferring into a single hot account. At that contention every
 * write conflicts, and a budget of 8 left roughly a third of threads failing
 * outright after ~2,200 version conflicts. 25 capped-backoff attempts absorb it.
 * The pessimistic strategy needs none of this -- it queues on the row lock
 * instead of discovering the conflict after the fact -- which is precisely the
 * trade the README's benchmark is about.
 */
@Component
public class RetryingLedgerFacade {

    private static final Logger log = LoggerFactory.getLogger(RetryingLedgerFacade.class);

    private final LedgerService ledger;
    private final Counter retryCounter;
    private final Counter exhaustedCounter;
    private final int maxAttempts;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;

    public RetryingLedgerFacade(LedgerService ledger,
                                MeterRegistry registry,
                                @Value("${ledger.retry.max-attempts:25}") int maxAttempts,
                                @Value("${ledger.retry.base-backoff-ms:5}") long baseBackoffMillis,
                                @Value("${ledger.retry.max-backoff-ms:200}") long maxBackoffMillis) {
        this.ledger = ledger;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMillis = baseBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.retryCounter = Counter.builder("ledger.concurrency.retries")
                .description("Transactions retried after a concurrency failure")
                .register(registry);
        this.exhaustedCounter = Counter.builder("ledger.concurrency.retries.exhausted")
                .description("Requests that failed after exhausting all retry attempts")
                .register(registry);
    }

    public IdempotentResult<EntryResponse> createEntry(String key, CreateEntryRequest request) {
        return withRetry("createEntry", () -> ledger.createEntry(key, request));
    }

    public IdempotentResult<EntryResponse> reverseEntry(String key, UUID entryId) {
        return withRetry("reverseEntry", () -> ledger.reverseEntry(key, entryId));
    }

    private <T> T withRetry(String operation, Supplier<T> action) {
        ConcurrencyFailureException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (ConcurrencyFailureException e) {
                // Covers both strategies: ObjectOptimisticLockingFailureException
                // from the @Version check, and CannotAcquireLockException from a
                // Postgres deadlock (40P01) or lock timeout under the pessimistic
                // strategy. Both mean "someone else got there first, try again".
                last = e;
                if (attempt == maxAttempts) {
                    break;
                }
                retryCounter.increment();
                sleepWithFullJitter(attempt);
                log.debug("{} retry {}/{} after {}", operation, attempt, maxAttempts,
                        e.getClass().getSimpleName());
            }
        }

        exhaustedCounter.increment();
        log.warn("{} exhausted {} attempts under contention", operation, maxAttempts);
        throw new ConcurrencyExhaustedException(operation, maxAttempts, last);
    }

    /**
     * Capped exponential backoff with full jitter: sleep a uniform random
     * duration in [0, min(base * 2^attempt, maxBackoff)).
     *
     * <p>The cap is what makes a large attempt budget usable. Uncapped, attempt
     * 25 would wait minutes, so raising the attempt count to survive a hot row
     * would instead just convert contention into a timeout. Capped, 25 attempts
     * costs at most a few seconds and the retries stay dense enough to actually
     * win the row.
     */
    private void sleepWithFullJitter(int attempt) {
        long ceiling = Math.min(baseBackoffMillis << Math.min(attempt - 1, 20), maxBackoffMillis);
        long delay = ThreadLocalRandom.current().nextLong(ceiling + 1);
        if (delay == 0) {
            Thread.onSpinWait();
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CannotAcquireLockException("interrupted while backing off", ie);
        }
    }

    @FunctionalInterface
    private interface Supplier<T> {
        T get();
    }
}
