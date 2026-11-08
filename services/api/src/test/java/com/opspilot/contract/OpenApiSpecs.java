package com.opspilot.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared by {@link OpenApiSpecDriftTest} (CI drift check — never writes) and {@code
 * OpenApiSpecGenerator} (the reproducible generator command — writes, never run by a bare {@code
 * mvn test}; deliberately named without a {@code Test} suffix so Surefire's default discovery glob
 * never picks it up). springdoc's {@code /v3/api-docs} needs no org membership, only a
 * validly-signed, unexpired JWT (T07's {@code anyRequest().authenticated()}), so a bare test-issuer
 * token with an arbitrary subject is enough.
 */
final class OpenApiSpecs {

  static final String AUDIENCE = "https://api.opspilot.local";

  /** {@code packages/contracts/openapi.json}, resolved from {@code services/api}'s own cwd. */
  static final Path OPENAPI_JSON_PATH = repoRoot().resolve("packages/contracts/openapi.json");

  private OpenApiSpecs() {}

  private static Path repoRoot() {
    return Paths.get("").toAbsolutePath().getParent().getParent();
  }

  static String fetchLiveSpec(MockMvc mockMvc, KeyPair testIssuerKeyPair) throws Exception {
    String token =
        TestJwtSigner.sign(
            testIssuerKeyPair,
            TestIssuerConfig.TEST_ISSUER,
            "openapi-generator",
            AUDIENCE,
            Instant.now().plusSeconds(60));
    return mockMvc
        .perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
