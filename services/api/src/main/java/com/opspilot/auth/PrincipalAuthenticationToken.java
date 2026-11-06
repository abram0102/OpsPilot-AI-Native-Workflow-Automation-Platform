package com.opspilot.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The {@link org.springframework.security.core.Authentication} used for every request once its JWT
 * has been validated: {@link #getPrincipal()} returns the resolved {@link AuthenticatedPrincipal},
 * not the raw {@link Jwt}, so {@code @AuthenticationPrincipal AuthenticatedPrincipal} works
 * directly in controllers without them ever touching JWT claims themselves.
 */
public class PrincipalAuthenticationToken extends AbstractAuthenticationToken {

  private final AuthenticatedPrincipal principal;
  private final Jwt jwt;

  public PrincipalAuthenticationToken(AuthenticatedPrincipal principal, Jwt jwt) {
    super(List.of());
    this.principal = principal;
    this.jwt = jwt;
    setAuthenticated(true);
  }

  @Override
  public Object getPrincipal() {
    return principal;
  }

  @Override
  public Object getCredentials() {
    return jwt;
  }
}
