package com.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Account balance, optionally as of a past instant.")
public record BalanceResponse(
        UUID accountId,
        String accountName,
        String accountType,
        String currency,

        @Schema(description = "Signed minor units. Debit-normal accounts read "
                + "positive when they hold value; credit-normal accounts read negative.")
        long balanceMinor,

        @Schema(description = "The same balance with the account's normal sign applied, "
                + "for display.")
        long normalBalanceMinor,

        @Schema(description = "The instant this balance is stated as of. Echoed back so "
                + "a caller who omitted as_of knows exactly what they got.")
        Instant asOf,

        @Schema(description = "true when recomputed from postings (as_of query), false "
                + "when read from the materialised balances table.")
        boolean recomputed
) {
}
