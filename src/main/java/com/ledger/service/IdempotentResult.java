package com.ledger.service;

/**
 * Carries whether a result was freshly created or replayed from a stored
 * response, so the controller can return 201 vs 200 without the service knowing
 * anything about HTTP.
 *
 * <p>A replay returns <b>200, not an error</b>. The client asked for an
 * operation to have happened exactly once; it has. Reporting that as a failure
 * would push retry logic back onto the caller, which is the problem idempotency
 * keys exist to remove.
 */
public record IdempotentResult<T>(T value, boolean replayed) {

    public static <T> IdempotentResult<T> created(T value) {
        return new IdempotentResult<>(value, false);
    }

    public static <T> IdempotentResult<T> replayed(T value) {
        return new IdempotentResult<>(value, true);
    }
}
