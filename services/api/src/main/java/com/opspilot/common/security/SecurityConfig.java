package com.opspilot.common.security;

import com.opspilot.auth.PrincipalResolvingJwtAuthenticationConverter;
import com.opspilot.auth.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * T07: real OIDC JWT validation (signature/issuer/audience/expiry, via {@link JwtDecoderConfig})
 * replaces T02's blanket {@code denyAll()}. Every path except the health probes now requires a
 * validly signed, correctly issued, correctly audienced, unexpired JWT — but authentication alone
 * grants no role; org-scoped authorization is {@link com.opspilot.auth.OrganizationAccessService}'s
 * job once T09/T10 add real endpoints. There is still no path to a 200 response anywhere but the
 * health probes, since no business endpoint exists yet (AGENTS.md: no debug auth bypass).
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtDecoder jwtDecoder, UserRepository users, ObjectMapper objectMapper)
      throws Exception {
    http.csrf(CsrfConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize ->
            authorize
                .requestMatchers("/actuator/health/**")
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(oauth2 ->
            oauth2
                .jwt(jwt ->
                    jwt.decoder(jwtDecoder)
                        .jwtAuthenticationConverter(
                            new PrincipalResolvingJwtAuthenticationConverter(users)))
                .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable);
    return http.build();
  }
}
