package com.ledger.error;

import org.springframework.http.HttpStatus;

/**
 * The same idempotency key was reused with a different request body.
 *
 * <p>This is a 409, not a 200 and not a silent overwrite. Reusing a key with a
 * different payload is unambiguously a client bug -- either a key-generation
 * collision or a retry that mutated the body -- and the only safe response is a
 * loud one. Returning the original response would hide a real defect; processing
 * the new body would break the guarantee the key exists to provide.
 *
 * <p>Matches Stripe's documented behaviour for idempotency key reuse.
 */
public class IdempotencyConflictException extends LedgerException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super(HttpStatus.CONFLICT,
                "https://ledger.example/problems/idempotency-key-reuse",
                "Idempotency key reused with a different request",
                "The Idempotency-Key '" + idempotencyKey + "' was already used for a "
                        + "request with a different body. Generate a new key for a new "
                        + "request, or resend the original body to replay the stored "
                        + "response.");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
