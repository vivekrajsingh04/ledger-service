package com.ledger.service;

import com.ledger.error.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor over {@code (created_at, id)}.
 *
 * <p>Base64 of {@code epochMicros|uuid}. Encoded rather than exposed so clients
 * cannot come to depend on the sort key -- changing the statement's ordering
 * later should not be a breaking API change.
 *
 * <p>Microsecond precision, matching Postgres {@code timestamptz}. Encoding
 * nanoseconds would produce a cursor that does not round-trip: the stored value
 * is truncated to microseconds, so a nanosecond-precision cursor could skip or
 * repeat the row it points at.
 */
public record Cursor(Instant createdAt, UUID id) {

    public static String encode(Instant createdAt, UUID id) {
        long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
        String raw = micros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            long micros = Long.parseLong(raw.substring(0, sep));
            Instant createdAt = Instant.ofEpochSecond(
                    Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L);
            return new Cursor(createdAt, UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new InvalidRequestException(
                    "cursor is not valid; pass back the nextCursor value verbatim");
        }
    }
}
