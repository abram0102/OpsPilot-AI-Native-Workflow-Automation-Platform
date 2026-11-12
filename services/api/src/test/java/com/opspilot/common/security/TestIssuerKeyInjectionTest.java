package com.opspilot.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * T29: the test issuer's key may be supplied from outside the JVM, so that a Playwright harness can
 * mint a token this API accepts.
 *
 * <p>That is a signing key arriving from configuration, which is exactly the shape of thing worth
 * being careful about, so the tests here are mostly about what it is <em>not</em> allowed to do.
 * The one that matters is {@link #aSuppliedKeyDoesNothingAtAllWhenTheTestIssuerIsOff()}: the
 * property is read inside a bean that does not exist unless {@code allow-test-issuer=true}, so the
 * key is inert on any deployment that has not deliberately switched the test issuer on — and {@link
 * ProductionSafetyGuard} already refuses to start at all if that flag is on in production (covered
 * by {@link ProductionSafetyGuardTest}).
 *
 * <p>No database and no Docker: this is bean wiring and key parsing.
 */
@Tag("security")
class TestIssuerKeyInjectionTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestIssuerConfig.class);

  @Test
  void aSuppliedKeyIsTheOneTheApplicationUses() throws Exception {
    KeyPair generatedOutside = generate();

    contextRunner
        .withPropertyValues(
            "app.security.allow-test-issuer=true",
            "app.security.test-issuer.private-key=" + base64Pkcs8(generatedOutside))
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(KeyPair.class);
          KeyPair inUse = context.getBean(KeyPair.class);

          // The modulus identifies the key pair. Comparing it rather than the encoded bytes keeps
          // this about "is it the same key", not about encoding details.
          assertThat(((RSAPrivateKey) inUse.getPrivate()).getModulus())
              .isEqualTo(((RSAPrivateKey) generatedOutside.getPrivate()).getModulus());
        });
  }

  @Test
  void theDerivedPublicHalfMatchesTheSuppliedPrivateOne() throws Exception {
    // The public key is derived from the private key's own modulus and exponent rather than being
    // supplied separately, so a mismatched pair is not expressible. Without this, the failure mode
    // would be every token failing its signature check for a reason that looks nothing like its
    // cause.
    KeyPair generatedOutside = generate();

    contextRunner
        .withPropertyValues(
            "app.security.allow-test-issuer=true",
            "app.security.test-issuer.private-key=" + base64Pkcs8(generatedOutside))
        .run(context -> {
          KeyPair inUse = context.getBean(KeyPair.class);
          assertThat(((RSAPublicKey) inUse.getPublic()).getModulus())
              .isEqualTo(((RSAPublicKey) generatedOutside.getPublic()).getModulus());
          assertThat(((RSAPublicKey) inUse.getPublic()).getPublicExponent())
              .isEqualTo(((RSAPublicKey) generatedOutside.getPublic()).getPublicExponent());
        });
  }

  @Test
  void aSuppliedKeyDoesNothingAtAllWhenTheTestIssuerIsOff() throws Exception {
    // The property is inert without the flag: no bean, so nothing can sign or verify with it.
    // This is the claim that makes the property safe to exist on a shipped application at all.
    contextRunner
        .withPropertyValues(
            "app.security.allow-test-issuer=false",
            "app.security.test-issuer.private-key=" + base64Pkcs8(generate()))
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KeyPair.class));
  }

  @Test
  void noSuppliedKeyStillMeansAFreshlyGeneratedOne() {
    // The default path, unchanged from before T29: a key per application start, none in source
    // control. Two separate contexts must not share one.
    contextRunner
        .withPropertyValues("app.security.allow-test-issuer=true")
        .run(first -> {
          java.math.BigInteger firstModulus =
              ((RSAPrivateKey) first.getBean(KeyPair.class).getPrivate()).getModulus();

          contextRunner
              .withPropertyValues("app.security.allow-test-issuer=true")
              .run(second -> {
                java.math.BigInteger secondModulus =
                    ((RSAPrivateKey) second.getBean(KeyPair.class).getPrivate()).getModulus();
                assertThat(secondModulus).isNotEqualTo(firstModulus);
              });
        });
  }

  @Test
  void anUnusableSuppliedKeyFailsStartupRatherThanSilentlyGeneratingOne() {
    // Falling back would be the worst outcome: the application starts, and every token the harness
    // mints is rejected as "invalid signature" — a failure that reads as a bug in the application
    // rather than as a mistyped environment variable.
    contextRunner
        .withPropertyValues(
            "app.security.allow-test-issuer=true",
            "app.security.test-issuer.private-key=this-is-not-a-key")
        .run(context ->
            // Asserted on the chain, not the root cause: the root is the base64 decoder's own
            // "Illegal base64 character", and the operator-facing sentence is deliberately the
            // wrapper's — keeping the real cause attached underneath it.
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("not a usable base64 PKCS#8 RSA private key")
                .hasStackTraceContaining("Illegal base64 character"));
  }

  private static KeyPair generate() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String base64Pkcs8(KeyPair keyPair) {
    return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
  }
}
