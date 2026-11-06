package com.opspilot.common.security;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the {@link JwtDecoder} the resource server actually uses. Two decoders exist:
 *
 * <ul>
 *   <li>the real one, built from {@code app.security.oidc-issuer-uri} (eventually the Auth0 tenant,
 *       T08) — marked {@link Lazy} so an unreachable/placeholder issuer never blocks application
 *       startup, only the decode of an actual token against it;
 *   <li>the test-only one (only present when {@code app.security.allow-test-issuer=true}),
 *       validated against the {@link TestIssuerConfig} key pair.
 * </ul>
 *
 * <p>Deliberately not using Spring Boot's auto-configured resource-server decoder: that
 * auto-configuration resolves the issuer eagerly at context startup from {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri}, which would make the app fail to start
 * whenever Auth0 isn't configured (true for all of local dev and CI before T08) — this project uses
 * its own {@code app.security.*} properties instead, specifically to keep decoder construction
 * lazy.
 */
@Configuration
public class JwtDecoderConfig {

  @Bean
  @Lazy
  JwtDecoder issuerJwtDecoder(
      @Value("${app.security.oidc-issuer-uri}") String issuerUri,
      @Value("${app.security.oidc-audience}") String audience) {
    NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(new AudienceValidator(audience)));
    return decoder;
  }

  @Bean
  @Primary
  JwtDecoder jwtDecoder(
      JwtDecoder issuerJwtDecoder,
      ObjectProvider<KeyPair> testIssuerKeyPair,
      @Value("${app.security.oidc-audience}") String audience,
      @Value("${app.security.allow-test-issuer:false}") boolean allowTestIssuer) {
    KeyPair keyPair = allowTestIssuer ? testIssuerKeyPair.getIfAvailable() : null;
    if (keyPair == null) {
      return issuerJwtDecoder;
    }
    JwtDecoder testIssuerDecoder = buildTestIssuerDecoder(keyPair, audience);
    return new TestFirstDelegatingJwtDecoder(testIssuerDecoder, issuerJwtDecoder);
  }

  private JwtDecoder buildTestIssuerDecoder(KeyPair keyPair, String audience) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
    OAuth2TokenValidator<Jwt> validator =
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator(TestIssuerConfig.TEST_ISSUER),
            new AudienceValidator(audience));
    decoder.setJwtValidator(validator);
    return decoder;
  }

  /**
   * Tries the test decoder first, falls back to the real one only if the test decoder rejects the
   * token. Deliberately NOT routed by peeking the token's own (unverified) {@code iss} claim: a
   * test-signed token that fraudulently claims some other issuer would then skip the test decoder
   * entirely, masking the actually meaningful rejection (wrong issuer, wrong audience, expired)
   * behind the real decoder's unrelated "can't reach placeholder issuer" failure. Trying the test
   * decoder first means its signature check runs regardless of claimed issuer, so a token signed
   * with the test key always gets evaluated — and genuinely rejected — by the validator whose
   * rejection reason actually matters; a token not signed with the test key simply fails that check
   * and falls through to the real decoder, exactly as if the test decoder didn't exist. Routing
   * this way grants no trust by itself — both decoders still fully verify signature, issuer,
   * audience and expiry independently, and the test key only exists in this process when {@code
   * allow-test-issuer} is explicitly on (never in production — see {@link ProductionSafetyGuard}).
   */
  private static final class TestFirstDelegatingJwtDecoder implements JwtDecoder {
    private final JwtDecoder testDecoder;
    private final JwtDecoder realDecoder;

    TestFirstDelegatingJwtDecoder(JwtDecoder testDecoder, JwtDecoder realDecoder) {
      this.testDecoder = testDecoder;
      this.realDecoder = realDecoder;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
      try {
        return testDecoder.decode(token);
      } catch (JwtException testFailure) {
        try {
          return realDecoder.decode(token);
        } catch (JwtException realFailure) {
          // Neither decoder accepted the token. In local/test
          // environments the real decoder's failure is almost
          // always just "issuer unreachable" noise (Auth0 isn't
          // configured), which would otherwise mask the actually
          // meaningful rejection reason from the test decoder.
          throw testFailure;
        }
      }
    }
  }
}
