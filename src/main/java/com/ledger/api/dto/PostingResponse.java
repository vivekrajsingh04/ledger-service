package com.ledger.api.dto;

import com.ledger.domain.Posting;
import java.time.Instant;
import java.util.UUID;

public record PostingResponse(
        UUID id,
        UUID entryId,
        UUID accountId,
        long amountMinor,
        String currency,
        String direction,
        Instant createdAt
) {
    public static PostingResponse from(Posting p) {
        return new PostingResponse(
                p.getId(), p.getEntryId(), p.getAccountId(), p.getAmountMinor(),
                p.getCurrency(), p.isDebit() ? "DEBIT" : "CREDIT", p.getCreatedAt());
    }
}
