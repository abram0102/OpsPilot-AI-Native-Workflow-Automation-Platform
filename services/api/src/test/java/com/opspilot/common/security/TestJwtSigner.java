package com.opspilot.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Mints RS256-signed test JWTs. Never used in production — test sources only. Public so integration
 * tests outside this package (e.g. {@code com.opspilot.auth}) can reuse it rather than duplicating
 * signing code.
 */
public final class TestJwtSigner {

  private TestJwtSigner() {}

  public static String sign(
      KeyPair keyPair, String issuer, String subject, String audience, Instant expiresAt) {
    return sign(keyPair, issuer, subject, audience, expiresAt, Map.of());
  }

  /**
   * Same as {@link #sign(KeyPair, String, String, String, Instant)}, with additional claims (e.g.
   * the namespaced profile claims {@link com.opspilot.auth.UserProvisioningService} reads).
   */
  public static String sign(
      KeyPair keyPair,
      String issuer,
      String subject,
      String audience,
      Instant expiresAt,
      Map<String, Object> extraClaims) {
    try {
      JWTClaimsSet.Builder builder =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject(subject)
              .audience(audience)
              .issueTime(Date.from(Instant.now().minusSeconds(5)))
              .expirationTime(Date.from(expiresAt));
      extraClaims.forEach(builder::claim);
      SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
      signedJwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
      return signedJwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign test JWT", e);
    }
  }
}
