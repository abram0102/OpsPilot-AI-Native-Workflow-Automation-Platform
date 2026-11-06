package com.opspilot.common.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Spring Security does not validate the `aud` claim by default even when built from an issuer
 * location — only this project's own backend audience (docs/API_CONTRACT.md: tokens are rejected as
 * 401 if the audience doesn't match) proves a token was actually minted for this API, not some
 * other client of the same OIDC tenant.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private final String requiredAudience;

  public AudienceValidator(String requiredAudience) {
    this.requiredAudience = requiredAudience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    if (jwt.getAudience() != null && jwt.getAudience().contains(requiredAudience)) {
      return OAuth2TokenValidatorResult.success();
    }
    OAuth2Error error =
        new OAuth2Error(
            "invalid_token", "The required audience " + requiredAudience + " is missing", null);
    return OAuth2TokenValidatorResult.failure(error);
  }
}
