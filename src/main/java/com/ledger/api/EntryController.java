package com.ledger.api;

import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.EntryResponse;
import com.ledger.service.IdempotentResult;
import com.ledger.service.QueryService;
import com.ledger.service.RetryingLedgerFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/entries")
@Validated
@Tag(name = "Entries", description = "Append-only double-entry journal")
public class EntryController {

    private final RetryingLedgerFacade ledger;
    private final QueryService queries;

    public EntryController(RetryingLedgerFacade ledger, QueryService queries) {
        this.ledger = ledger;
        this.queries = queries;
    }

    @PostMapping
    @Operation(
            summary = "Create a journal entry",
            description = """
                    Postings must sum to zero per currency; the database enforces \
                    this with a deferred constraint trigger independently of this \
                    service.

                    The Idempotency-Key header is required. Sending the same key \
                    with the same body returns the original response with 200. \
                    Sending the same key with a different body returns 409 -- key \
                    reuse with a changed payload is a client bug and is reported \
                    loudly rather than resolved silently.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Entry created"),
            @ApiResponse(responseCode = "200",
                    description = "Replayed: this key was already used with this body"),
            @ApiResponse(responseCode = "409",
                    description = "This key was already used with a different body"),
            @ApiResponse(responseCode = "422", description = "Postings do not sum to zero"),
            @ApiResponse(responseCode = "503", description = "Contention; retry with backoff")
    })
    public ResponseEntity<EntryResponse> create(
            @Parameter(description = "Client-generated unique key for this request",
                    required = true, example = "3f2a1c7e-payout-8891")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateEntryRequest request) {

        IdempotentResult<EntryResponse> result = ledger.createEntry(idempotencyKey, request);
        return respond(result);
    }

    @PostMapping("/{id}/reverse")
    @Operation(
            summary = "Reverse an entry",
            description = """
                    Writes a NEW entry with mirrored postings, linked by \
                    reverses_entry_id. The original entry is never updated or \
                    deleted -- a regulator must be able to reconstruct the balance \
                    as of any past instant, and mutating history destroys that.

                    An entry can be reversed at most once; a partial unique index \
                    on reverses_entry_id enforces it even under concurrent calls.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversal entry created"),
            @ApiResponse(responseCode = "200", description = "Replayed"),
            @ApiResponse(responseCode = "409", description = "Already reversed"),
            @ApiResponse(responseCode = "404", description = "No such entry")
    })
    public ResponseEntity<EntryResponse> reverse(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @PathVariable UUID id) {

        return respond(ledger.reverseEntry(idempotencyKey, id));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch an entry and its postings")
    public EntryResponse get(@PathVariable UUID id) {
        return queries.entry(id);
    }

    private static ResponseEntity<EntryResponse> respond(IdempotentResult<EntryResponse> result) {
        if (result.replayed()) {
            // 200, not 201 and not an error: the operation the client asked for
            // has happened exactly once, which is what they wanted.
            return ResponseEntity.ok()
                    .header("Idempotent-Replay", "true")
                    .body(result.value());
        }
        return ResponseEntity
                .created(URI.create("/v1/entries/" + result.value().id()))
                .body(result.value());
    }
}
