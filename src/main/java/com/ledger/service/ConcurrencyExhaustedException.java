package com.ledger.service;

import com.ledger.error.LedgerException;
import org.springframework.http.HttpStatus;

/**
 * All retry attempts were consumed and the write still could not be applied.
 *
 * <p>503 with a Retry-After, not 500: nothing is wrong with the request, the
 * account is simply too hot right now. Telling the client to come back is honest
 * and actionable; a 500 would send them looking for a bug that isn't there.
 */
public class ConcurrencyExhaustedException extends LedgerException {

    public ConcurrencyExhaustedException(String operation, int attempts, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "https://ledger.example/problems/concurrency-exhausted",
                "Too much contention on this account",
                operation + " failed after " + attempts + " attempts because another "
                        + "transaction kept modifying the same account balance. Retry "
                        + "with backoff.");
        if (cause != null) {
            initCause(cause);
        }
    }
}
