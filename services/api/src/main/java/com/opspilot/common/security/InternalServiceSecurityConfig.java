package com.opspilot.common.security;

import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Machine-only identity for the private internal action API (docs/TASKS.md T21).
 *
 * <p>This is a second, separately ordered filter chain scoped to {@code /internal/**}, and the
 * separation is the security property — not a convenience. The two chains validate against
 * different decoders entirely: a browser's user token is signed by the OIDC issuer for the {@code
 * api} audience, while this chain accepts only an HS256 token signed with the shared service secret
 * for the {@code internal} audience. A user bearer presented here therefore fails on signature
 * *and* audience, rather than relying on anyone remembering a role check (docs/API_CONTRACT.md:
 * "reject normal browser/user tokens").
 *
 * <p>Deliberately absent: {@link com.opspilot.auth.PrincipalResolvingJwtAuthenticationConverter}. A
 * service token identifies a worker, not a person — there is no {@code users} row to resolve, and
 * wiring the user converter here would be the first step toward treating a machine as one.
 *
 * <p>Network privacy is expected on top of this, not instead of it
 * (docs/PRODUCT_AND_ARCHITECTURE.md: "protected by machine credentials, not just network
 * isolation").
 */
/**
 * Only in a servlet application.
 *
 * <p>Without this the whole context fails to start whenever the web server is switched off, because
 * {@code HttpSecurity} is a servlet-scoped bean. That is not hypothetical: {@code make seed-demo}
 * runs under {@code application-seed.yml}'s {@code web-application-type: none} and had been unable
 * to start at all — "No qualifying bean of type HttpSecurity" — since a second filter chain was
 * added. Nothing caught it because no test runs with the web server disabled, and the seeder is a
 * command, not an endpoint.
 *
 * <p>HTTP security configuration belongs to an HTTP application, so making it conditional is the
 * honest fix rather than giving the seeder a web server it has no use for.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalServiceSecurityConfig {

  public static final String INTERNAL_PATH_PATTERN = "/internal/**";

  private final String audience;
  private final String signingSecret;

  InternalServiceSecurityConfig(
      @Value("${app.security.internal-service.audience}") String audience,
      @Value("${app.security.internal-service.signing-secret:}") String signingSecret) {
    this.audience = audience;
    this.signingSecret = signingSecret;
  }

  /**
   * Ordered ahead of the user chain so {@code /internal/**} is matched here first; without an
   * explicit order the user chain's {@code anyRequest()} could claim these paths.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 10)
  SecurityFilterChain internalServiceFilterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      // @Qualifier is load-bearing, not decoration. Two JwtDecoder beans exist and the user-facing
      // one is @Primary; Spring resolves @Primary *before* falling back to matching a parameter's
      // name against a bean name, so a parameter merely *named* internalServiceJwtDecoder was
      // silently injected the user decoder — which made this chain accept browser tokens and
      // reject the worker's. Caught by the tests, not by review (docs/DECISIONS.md ADR-045).
      @Qualifier("internalServiceJwtDecoder") JwtDecoder internalServiceJwtDecoder)
      throws Exception {
    http.securityMatcher(INTERNAL_PATH_PATTERN)
        .csrf(CsrfConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 ->
            oauth2
                // No principal-resolving converter: the default leaves a JwtAuthenticationToken,
                // which carries the service's claims and no user identity at all.
                .jwt(jwt -> jwt.decoder(internalServiceJwtDecoder))
                .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable);
    return http.build();
  }

  /**
   * HS256 against the shared secret, plus audience and expiry validation.
   *
   * <p>Symmetric because both parties are ours and the secret is deployed to both ({@code
   * INTERNAL_SERVICE_SIGNING_SECRET}). The decoder is built eagerly, unlike the OIDC one, because
   * it needs no network discovery — but it fails fast when no secret is configured rather than
   * quietly accepting nothing.
   */
  @Bean
  JwtDecoder internalServiceJwtDecoder() {
    if (!StringUtils.hasText(signingSecret)) {
      // Refusing to build a decoder is what makes the endpoint unreachable rather than
      // accidentally open when the secret is missing.
      return token -> {
        throw new org.springframework.security.oauth2.jwt.BadJwtException(
            "The internal service API is not configured on this instance.");
      };
    }

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(
                new SecretKeySpec(
                    signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();

    OAuth2TokenValidator<Jwt> validators =
        new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(), new AudienceValidator(audience));
    decoder.setJwtValidator(validators);
    return decoder;
  }

  /** The worker identity a validated service token represents. */
  public static String serviceSubject(JwtAuthenticationToken token) {
    return token.getToken().getSubject();
  }
}
