package com.opspilot.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Skeleton security posture for T02: only the health probes are reachable without authentication;
 * every other path is denied outright.
 *
 * <p>This is deliberately NOT the final policy — ticket T07 replaces {@code anyRequest().denyAll()}
 * with real OIDC JWT validation and role-based {@code authorizeHttpRequests} rules
 * (docs/PRODUCT_AND_ARCHITECTURE.md section 1, docs/API_CONTRACT.md). Until then there is
 * intentionally no path to a 200 response other than the two permitted health endpoints — AGENTS.md
 * forbids a "debug auth bypass", so the safe default while business endpoints don't exist yet is to
 * deny, not to permit-all.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(CsrfConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize ->
            authorize.requestMatchers("/actuator/health/**").permitAll().anyRequest().denyAll())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable);
    return http.build();
  }
}
