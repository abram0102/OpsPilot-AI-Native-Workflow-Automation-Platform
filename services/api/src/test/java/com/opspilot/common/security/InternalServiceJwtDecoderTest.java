package com.opspilot.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The service decoder in isolation — no Spring context, no database, so it runs everywhere.
 *
 * <p>It cannot prove the decoder is *wired* to the internal filter chain (that needs a context, and
 * a wiring bug there is what `InternalAgentActionHttpIntegrationTest` actually caught). What it
 * does prove is that the decoder itself is strict about the two things separating a worker
 * credential from a user one: the signing key and the audience.
 */
@Tag("security")
class InternalServiceJwtDecoderTest {

  private static final String SECRET = "a-local-test-secret-long-enough-for-hs256-signing";
  private static final String SERVICE_AUDIENCE = "https://internal.opspilot.local";
  private static final String USER_AUDIENCE = "https://api.opspilot.local";

  private JwtDecoder decoder() {
    return new InternalServiceSecurityConfig(SERVICE_AUDIENCE, SECRET).internalServiceJwtDecoder();
  }

  @Test
  void acceptsAServiceTokenSignedWithTheConfiguredSecret() {
    String token =
        TestServiceJwtSigner.sign(
            SECRET, "opspilot-agent-worker", SERVICE_AUDIENCE, Instant.now().plusSeconds(60));

    assertThat(decoder().decode(token).getSubject()).isEqualTo("opspilot-agent-worker");
  }

  @Test
  void rejectsATokenSignedWithAnotherSecret() {
    String token =
        TestServiceJwtSigner.sign(
            "a-completely-different-secret-also-long-enough",
            "opspilot-agent-worker",
            SERVICE_AUDIENCE,
            Instant.now().plusSeconds(60));

    assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsATokenMintedForTheUserAudience() {
    // Right key, wrong audience. The audience split is the thing that keeps a worker credential
    // and a user credential from being interchangeable, so it is checked independently.
    String token =
        TestServiceJwtSigner.sign(
            SECRET, "opspilot-agent-worker", USER_AUDIENCE, Instant.now().plusSeconds(60));

    assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsAnExpiredToken() {
    String token =
        TestServiceJwtSigner.sign(
            SECRET, "opspilot-agent-worker", SERVICE_AUDIENCE, Instant.now().minusSeconds(60));

    assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void refusesEveryTokenWhenNoSecretIsConfigured() {
    // An unconfigured instance must be unreachable rather than accidentally open.
    JwtDecoder unconfigured =
        new InternalServiceSecurityConfig(SERVICE_AUDIENCE, "").internalServiceJwtDecoder();
    String token =
        TestServiceJwtSigner.sign(
            SECRET, "opspilot-agent-worker", SERVICE_AUDIENCE, Instant.now().plusSeconds(60));

    assertThatThrownBy(() -> unconfigured.decode(token)).isInstanceOf(JwtException.class);
  }
}
