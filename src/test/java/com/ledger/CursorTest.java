package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.error.InvalidRequestException;
import com.ledger.service.Cursor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure unit tests -- no database, no Spring context. */
@DisplayName("keyset cursor encoding")
class CursorTest {

    @Test
    @DisplayName("round-trips at microsecond precision, matching Postgres timestamptz")
    void roundTripsAtMicrosecondPrecision() {
        Instant instant = Instant.parse("2026-03-01T12:34:56.123456Z");
        UUID id = UUID.randomUUID();

        Cursor decoded = Cursor.decode(Cursor.encode(instant, id));

        assertThat(decoded.createdAt()).isEqualTo(instant);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("nanosecond input truncates to microseconds rather than drifting")
    void nanosecondsTruncateDeterministically() {
        // Postgres stores microseconds. A cursor that claimed nanosecond precision
        // would point at an instant no row can have, and the keyset comparison
        // would then skip or repeat a row at the page boundary.
        Instant withNanos = Instant.parse("2026-03-01T12:34:56.123456789Z");
        Cursor decoded = Cursor.decode(Cursor.encode(withNanos, UUID.randomUUID()));

        assertThat(decoded.createdAt())
                .isEqualTo(Instant.parse("2026-03-01T12:34:56.123456Z"));
    }

    @Test
    @DisplayName("pre-epoch instants round-trip (floorDiv, not integer division)")
    void preEpochInstantsRoundTrip() {
        Instant instant = Instant.parse("1969-07-20T20:17:40.000500Z");
        Cursor decoded = Cursor.decode(Cursor.encode(instant, UUID.randomUUID()));
        assertThat(decoded.createdAt()).isEqualTo(instant);
    }

    @Test
    @DisplayName("the encoding is opaque and URL-safe")
    void encodingIsUrlSafe() {
        String encoded = Cursor.encode(Instant.now(), UUID.randomUUID());
        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("a malformed cursor is a client error, not a crash")
    void malformedCursorIsARequestError() {
        assertThatThrownBy(() -> Cursor.decode("!!!not-base64!!!"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> Cursor.decode("bm90LWEtY3Vyc29y"))  // "not-a-cursor"
                .isInstanceOf(InvalidRequestException.class);
    }
}
