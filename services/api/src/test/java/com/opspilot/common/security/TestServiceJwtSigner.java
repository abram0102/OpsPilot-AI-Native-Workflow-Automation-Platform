package com.opspilot.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;

/**
 * Mints HS256 service tokens for tests.
 *
 * <p>Separate from {@link TestJwtSigner}, which is RSA-only: user tokens are signed by an OIDC
 * issuer's key pair, service tokens by the shared internal secret. Keeping two signers mirrors the
 * fact that these are genuinely different credentials — a test cannot accidentally mint a service
 * token with the user key pair, which is precisely the confusion the production code refuses.
 */
public final class TestServiceJwtSigner {

  /** The local-profile default from {@code application-local.yml}. */
  public static final String LOCAL_SECRET =
      "local-development-only-internal-service-secret-not-for-production";

  public static final String SERVICE_AUDIENCE = "https://internal.opspilot.local";

  private TestServiceJwtSigner() {}

  public static String sign(String secret, String subject, String audience, Instant expiresAt) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .audience(audience)
              .issueTime(Date.from(Instant.now().minusSeconds(5)))
              .expirationTime(Date.from(expiresAt))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not sign a test service token.", e);
    }
  }

  /** A valid worker token for the local secret and service audience. */
  public static String validWorkerToken() {
    return sign(LOCAL_SECRET, "opspilot-agent-worker", SERVICE_AUDIENCE, hourFromNow());
  }

  private static Instant hourFromNow() {
    return Instant.now().plusSeconds(3600);
  }
}
