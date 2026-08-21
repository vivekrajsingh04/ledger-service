package com.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.PostingRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Canonical hash of a request body, used to decide whether a reused idempotency
 * key carries the same request or a different one.
 *
 * <p>Hashing the raw bytes would be wrong: two byte-different bodies can be the
 * same request. Key order in JSON is not semantic, and neither is the order of
 * postings within an entry -- a client that retries with its postings serialised
 * in a different order is retrying, not sending a new request, and must get the
 * replayed response rather than a 409.
 *
 * <p>So we canonicalise first: sort postings by (accountId, amount, currency),
 * normalise a null description to empty, then hash the resulting JSON.
 */
@Component
public class RequestHasher {

    private static final Comparator<PostingRequest> CANONICAL_ORDER =
            Comparator.comparing((PostingRequest p) -> p.accountId().toString())
                    .thenComparing(PostingRequest::amountMinor)
                    .thenComparing(PostingRequest::currency);

    private final ObjectMapper mapper;

    public RequestHasher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(CreateEntryRequest request) {
        List<PostingRequest> canonical = request.postings().stream()
                .sorted(CANONICAL_ORDER)
                .toList();
        String description = request.description() == null ? "" : request.description();
        return sha256(serialise(new CreateEntryRequest(description, canonical)));
    }

    /** Reversals carry no body, so the hash is over the entry being reversed. */
    public String hashReversal(String entryId) {
        return sha256("reverse:" + entryId);
    }

    private String serialise(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot canonicalise request for hashing", e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
