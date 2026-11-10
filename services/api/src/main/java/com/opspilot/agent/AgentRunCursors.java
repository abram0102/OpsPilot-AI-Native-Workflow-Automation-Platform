package com.opspilot.agent;

import com.opspilot.common.web.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursors for the run explorer, stable by {@code (created_at, id)} — the same scheme
 * the ticket list uses, kept separate so the two lists' cursors are not interchangeable.
 */
final class AgentRunCursors {

  private static final String SEPARATOR = "|";

  private AgentRunCursors() {}

  static String encode(Instant createdAt, UUID id) {
    String raw = createdAt.toString() + SEPARATOR + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static Decoded decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int separatorIndex = raw.indexOf(SEPARATOR);
      if (separatorIndex < 0) {
        throw new IllegalArgumentException("missing separator");
      }
      Instant createdAt = Instant.parse(raw.substring(0, separatorIndex));
      UUID id = UUID.fromString(raw.substring(separatorIndex + 1));
      return new Decoded(createdAt, id);
    } catch (RuntimeException e) {
      throw new BadRequestException("Invalid pagination cursor.");
    }
  }

  record Decoded(Instant createdAt, UUID id) {}
}
