package com.opspilot.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves a validated JWT's {@code sub} claim against {@link User} exactly once per request,
 * server-side — this is the only place a request's identity is decided, never a client-supplied
 * header or claim the app merely trusts (AGENTS.md rule 11).
 */
public class PrincipalResolvingJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  private final UserRepository users;

  public PrincipalResolvingJwtAuthenticationConverter(UserRepository users) {
    this.users = users;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String authSubject = jwt.getSubject();
    AuthenticatedPrincipal principal =
        users
            .findByAuthSubject(authSubject)
            .map(user -> AuthenticatedPrincipal.resolved(user.getId(), authSubject))
            .orElseGet(() -> AuthenticatedPrincipal.unresolved(authSubject));
    return new PrincipalAuthenticationToken(principal, jwt);
  }
}
