package com.ledger.api;

import com.ledger.api.dto.BalanceResponse;
import com.ledger.api.dto.StatementPage;
import com.ledger.service.QueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Accounts", description = "Balances and statements")
public class AccountController {

    private final QueryService queries;

    public AccountController(QueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/{id}/balance")
    @Operation(
            summary = "Account balance, optionally as of a past instant",
            description = """
                    Without as_of, returns the materialised balance (O(1)).

                    With as_of, recomputes from postings up to that instant. This \
                    is possible only because the ledger is append-only: no posting \
                    is ever updated or deleted, so any past balance is derivable \
                    exactly. That is what regulatory reconstructability means in \
                    practice.""")
    public BalanceResponse balance(
            @PathVariable UUID id,
            @Parameter(description = "ISO-8601 instant, e.g. 2026-03-01T00:00:00Z",
                    example = "2026-03-01T00:00:00Z")
            @RequestParam(name = "as_of", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {

        return queries.balance(id, asOf);
    }

    @GetMapping("/{id}/statement")
    @Operation(
            summary = "Account statement, cursor-paginated",
            description = """
                    Keyset pagination over (created_at, id), newest first. Not \
                    OFFSET: on an append-only table under concurrent writes, rows \
                    inserted between two page requests shift the OFFSET window, so \
                    page 2 re-shows rows from page 1 and skips others. A cursor \
                    names a position in the data rather than a count of rows, so it \
                    stays correct while the table grows underneath it.

                    Treat nextCursor as opaque and pass it back verbatim.""")
    public StatementPage statement(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return queries.statement(id, cursor, limit);
    }
}
