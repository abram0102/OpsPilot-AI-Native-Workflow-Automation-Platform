package com.opspilot.common.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The local test-only OIDC issuer (docs/MANUAL_SETUP.md Gate H1: "a test-only signed OIDC
 * issuer/token fixture restricted to test builds, not a dev bypass exposed on production routes").
 * The key pair exists only when {@code app.security.allow-test-issuer=true} — never true in
 * production, enforced separately by {@link ProductionSafetyGuard} — so there is no test signing
 * key material anywhere in a production process's memory.
 *
 * <p><b>Where the key comes from.</b> By default it is generated fresh per application start, so no
 * key material of any kind — "test-only" or otherwise — lives in source control. Tests inside this
 * JVM inject this same {@link KeyPair} bean rather than keeping their own copy.
 *
 * <p>T29 added the other case: an <b>out-of-process</b> harness (Playwright) has to mint a token
 * this API will accept, and it cannot reach into the JVM for a generated key. So {@code
 * app.security.test-issuer.private-key} may supply one, as base64 PKCS#8. The E2E harness
 * <em>generates that key itself, per run,</em> and passes it to the API process as an environment
 * variable — it is never written to this repository, so the invariant above is unchanged: a key in
 * source control was the thing to avoid, not a key in a test process's memory.
 *
 * <p>Note what this property cannot do. It is read only inside a bean that does not exist unless
 * {@code allow-test-issuer=true}, so setting it on a production deployment achieves nothing: there
 * is no test decoder for the key to serve, and {@link ProductionSafetyGuard} refuses to start the
 * application if the flag is on under the {@code production} profile at all.
 */
@Configuration
public class TestIssuerConfig {

  public static final String TEST_ISSUER = "https://test-issuer.opspilot.local/";

  @Bean
  @ConditionalOnProperty(name = "app.security.allow-test-issuer", havingValue = "true")
  KeyPair testIssuerKeyPair(
      @Value("${app.security.test-issuer.private-key:}") String suppliedPrivateKey) {
    if (suppliedPrivateKey == null || suppliedPrivateKey.isBlank()) {
      return generateKeyPair();
    }
    return keyPairFrom(suppliedPrivateKey);
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key generation must always be available", e);
    }
  }

  /**
   * Rebuilds the pair from a base64 PKCS#8 private key.
   *
   * <p>The public half is derived from the private key's own modulus and public exponent rather
   * than being supplied separately, so there is no way to hand this a mismatched pair — the
   * signature check would then fail for a reason that looks nothing like its cause.
   */
  private static KeyPair keyPairFrom(String base64Pkcs8) {
    try {
      byte[] der = Base64.getDecoder().decode(base64Pkcs8.replaceAll("\\s", ""));
      KeyFactory rsa = KeyFactory.getInstance("RSA");
      RSAPrivateCrtKey privateKey =
          (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(der));
      PublicKey publicKey =
          rsa.generatePublic(
              new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
      return new KeyPair(publicKey, privateKey);
    } catch (RuntimeException | java.security.GeneralSecurityException e) {
      // Deliberately fatal. A harness that supplied an unusable key would otherwise get a silently
      // generated one instead, and every token it mints would be rejected with "invalid signature"
      // — a failure that looks like a bug in the application rather than a mistyped environment
      // variable.
      throw new IllegalStateException(
          "app.security.test-issuer.private-key is set but is not a usable base64 PKCS#8 RSA"
              + " private key. Refusing to fall back to a generated key, which would make every"
              + " externally minted token fail its signature check instead.",
          e);
    }
  }
}
