package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.api.dto.CreateEntryRequest;
import com.ledger.api.dto.PostingRequest;
import com.ledger.service.RequestHasher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hash decides whether a reused idempotency key is a retry (replay) or a
 * client bug (409), so what it does and does not consider is a correctness
 * question, not an implementation detail.
 */
@DisplayName("canonical request hashing")
class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher(new ObjectMapper());

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static CreateEntryRequest entry(String description, PostingRequest... legs) {
        return new CreateEntryRequest(description, List.of(legs));
    }

    @Test
    @DisplayName("identical requests hash identically")
    void identicalRequestsHashTheSame() {
        CreateEntryRequest one = entry("pay",
                new PostingRequest(A, 100L, "INR"), new PostingRequest(B, -100L, "INR"));
        CreateEntryRequest two = entry("pay",
                new PostingRequest(A, 100L, "INR"), new PostingRequest(B, -100L, "INR"));

        assertThat(hasher.hash(one)).isEqualTo(hasher.hash(two));
    }

    @Test
    @DisplayName("posting order is not semantic, so it must not change the hash")
    void postingOrderDoesNotChangeTheHash() {
        CreateEntryRequest forward = entry("pay",
                new PostingRequest(A, 100L, "INR"), new PostingRequest(B, -100L, "INR"));
        CreateEntryRequest reversed = entry("pay",
                new PostingRequest(B, -100L, "INR"), new PostingRequest(A, 100L, "INR"));

        assertThat(hasher.hash(forward))
                .as("a client whose JSON library reorders a list is still retrying")
                .isEqualTo(hasher.hash(reversed));
    }

    @Test
    @DisplayName("a null description and an empty one are the same request")
    void nullDescriptionNormalises() {
        assertThat(hasher.hash(entry(null,
                new PostingRequest(A, 5L, "INR"), new PostingRequest(B, -5L, "INR"))))
                .isEqualTo(hasher.hash(entry("",
                        new PostingRequest(A, 5L, "INR"), new PostingRequest(B, -5L, "INR"))));
    }

    @Test
    @DisplayName("a different amount is a different request")
    void differentAmountChangesTheHash() {
        assertThat(hasher.hash(entry("pay",
                new PostingRequest(A, 100L, "INR"), new PostingRequest(B, -100L, "INR"))))
                .isNotEqualTo(hasher.hash(entry("pay",
                        new PostingRequest(A, 101L, "INR"), new PostingRequest(B, -101L, "INR"))));
    }

    @Test
    @DisplayName("a different account is a different request")
    void differentAccountChangesTheHash() {
        UUID c = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        assertThat(hasher.hash(entry("pay",
                new PostingRequest(A, 100L, "INR"), new PostingRequest(B, -100L, "INR"))))
                .isNotEqualTo(hasher.hash(entry("pay",
                        new PostingRequest(A, 100L, "INR"), new PostingRequest(c, -100L, "INR"))));
    }

    @Test
    @DisplayName("a different description is a different request")
    void differentDescriptionChangesTheHash() {
        assertThat(hasher.hash(entry("payout",
                new PostingRequest(A, 1L, "INR"), new PostingRequest(B, -1L, "INR"))))
                .isNotEqualTo(hasher.hash(entry("refund",
                        new PostingRequest(A, 1L, "INR"), new PostingRequest(B, -1L, "INR"))));
    }
}
