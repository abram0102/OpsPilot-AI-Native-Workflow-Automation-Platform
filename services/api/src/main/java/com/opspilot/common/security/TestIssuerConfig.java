package com.opspilot.common.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
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
 * <p>Generated fresh per application start rather than hardcoded, so no key material of any kind —
 * "test-only" or otherwise — lives in source control. Tests needing to mint a test-signed token
 * inject this same {@link KeyPair} bean rather than keeping their own copy.
 */
@Configuration
public class TestIssuerConfig {

  public static final String TEST_ISSUER = "https://test-issuer.opspilot.local/";

  @Bean
  @ConditionalOnProperty(name = "app.security.allow-test-issuer", havingValue = "true")
  KeyPair testIssuerKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key generation must always be available", e);
    }
  }
}
