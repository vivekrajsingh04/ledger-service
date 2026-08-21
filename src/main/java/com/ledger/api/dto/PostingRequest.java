package com.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * One leg of a requested entry.
 *
 * @param amountMinor signed minor units: positive debits the account, negative
 *                    credits it. An integer type, deliberately -- see
 *                    {@link com.ledger.domain.Posting}.
 */
public record PostingRequest(
        @NotNull
        @Schema(example = "00000000-0000-0000-0000-000000000001")
        UUID accountId,

        @NotNull
        @Schema(description = "Signed minor units. Positive = debit, negative = credit.",
                example = "150000")
        Long amountMinor,

        @NotNull
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code")
        @Schema(example = "INR")
        String currency
) {
}
