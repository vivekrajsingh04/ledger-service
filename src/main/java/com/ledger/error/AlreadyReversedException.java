package com.ledger.error;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * An entry may be reversed at most once.
 *
 * <p>Also enforced by a partial unique index on {@code reverses_entry_id}, so two
 * concurrent reversals cannot both succeed even if both pass the service check.
 */
public class AlreadyReversedException extends LedgerException {

    public AlreadyReversedException(UUID entryId, UUID reversalId) {
        super(HttpStatus.CONFLICT,
                "https://ledger.example/problems/already-reversed",
                "Entry already reversed",
                "Entry " + entryId + " was already reversed by entry " + reversalId
                        + ". Reversing a reversal is a new correction, not a repeat.");
    }
}
