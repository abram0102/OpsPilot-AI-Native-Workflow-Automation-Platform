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

/**
 * Mints RS256-signed test JWTs. Never used in production — test sources only. Public so integration
 * tests outside this package (e.g. {@code com.opspilot.auth}) can reuse it rather than duplicating
 * signing code.
 */
public final class TestJwtSigner {

  private TestJwtSigner() {}

  public static String sign(
      KeyPair keyPair, String issuer, String subject, String audience, Instant expiresAt) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject(subject)
              .audience(audience)
              .issueTime(Date.from(Instant.now().minusSeconds(5)))
              .expirationTime(Date.from(expiresAt))
              .build();
      SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      signedJwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
      return signedJwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign test JWT", e);
    }
  }
}
