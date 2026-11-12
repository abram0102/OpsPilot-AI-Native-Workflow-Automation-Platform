package com.opspilot.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T07 acceptance: "no bearer/wrong issuer/wrong audience/expired token tests fail auth". Exercises
 * the real, fully-wired {@link JwtDecoderConfig} composite decoder — not a simplified test-only
 * override — against the {@code local} profile's actual {@code allow-test-issuer=true} default.
 * Needs a real Postgres via Testcontainers: {@link SecurityConfig} now depends on {@code
 * UserRepository} to resolve principals, so even these pure authentication (not authorization)
 * checks need a working datasource for the application context to start at all.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Tag("security")
class JwtAuthenticationIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;

  @Test
  void noBearerTokenIsRejected() throws Exception {
    mockMvc
        .perform(get("/__test/protected"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  @Test
  void invalidSignatureIsRejected() throws Exception {
    KeyPair unrelatedKeyPair = generateRsaKeyPair();
    String token =
        TestJwtSigner.sign(
            unrelatedKeyPair,
            TestIssuerConfig.TEST_ISSUER,
            "auth|someone",
            AUDIENCE,
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(get("/__test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongIssuerIsRejected() throws Exception {
    String token =
        TestJwtSigner.sign(
            testIssuerKeyPair,
            "https://wrong-issuer.example.com/",
            "auth|someone",
            AUDIENCE,
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(get("/__test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongAudienceIsRejected() throws Exception {
    String token =
        TestJwtSigner.sign(
            testIssuerKeyPair,
            TestIssuerConfig.TEST_ISSUER,
            "auth|someone",
            "https://wrong-audience.example.com/",
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(get("/__test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    String token =
        TestJwtSigner.sign(
            testIssuerKeyPair,
            TestIssuerConfig.TEST_ISSUER,
            "auth|someone",
            AUDIENCE,
            Instant.now().minusSeconds(60));

    mockMvc
        .perform(get("/__test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validlySignedTokenIsAccepted_evenForAnUnresolvedSubject() throws Exception {
    // T08 (not this ticket) provisions a User row on first login; until
    // then a validly signed token for an unknown subject still
    // authenticates (the pipeline itself works) but simply has no
    // memberships anywhere — proven separately by
    // OrganizationAccessHttpIntegrationTest.
    String token =
        TestJwtSigner.sign(
            testIssuerKeyPair,
            TestIssuerConfig.TEST_ISSUER,
            "auth|not-yet-provisioned",
            AUDIENCE,
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(get("/__test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authSubject").value("auth|not-yet-provisioned"));
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
