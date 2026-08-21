package com.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "A double-entry journal entry. Postings must sum to zero per currency.")
public record CreateEntryRequest(
        @Size(max = 500)
        String description,

        @NotEmpty
        @Size(min = 2, message = "a double-entry entry needs at least two postings")
        @Valid
        List<PostingRequest> postings
) {
}
