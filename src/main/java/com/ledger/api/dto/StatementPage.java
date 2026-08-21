package com.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of an account statement, cursor-paginated.")
public record StatementPage(
        List<PostingResponse> items,

        @Schema(description = "Opaque cursor for the next page, or null at the end. "
                + "Pass it back as ?cursor=. Do not parse it.",
                example = "MTcwOTIwMDAwMDAwMHw3Yzlm...")
        String nextCursor,

        boolean hasMore
) {
}
