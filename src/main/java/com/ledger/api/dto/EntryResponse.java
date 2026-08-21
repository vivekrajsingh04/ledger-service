package com.ledger.api.dto;

import com.ledger.domain.JournalEntry;
import com.ledger.domain.Posting;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EntryResponse(
        UUID id,
        String description,
        UUID reversesEntryId,
        Instant createdAt,
        List<PostingResponse> postings
) {
    public static EntryResponse from(JournalEntry entry, List<Posting> postings) {
        return new EntryResponse(
                entry.getId(),
                entry.getDescription(),
                entry.getReversesEntryId(),
                entry.getCreatedAt(),
                postings.stream().map(PostingResponse::from).toList());
    }
}
