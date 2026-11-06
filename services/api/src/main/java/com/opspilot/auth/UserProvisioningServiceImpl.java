package com.opspilot.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProvisioningServiceImpl implements UserProvisioningService {

  private final UserRepository users;

  public UserProvisioningServiceImpl(UserRepository users) {
    this.users = users;
  }

  @Override
  @Transactional
  public User ensureProvisioned(Jwt jwt) {
    String authSubject = jwt.getSubject();
    return users.findByAuthSubject(authSubject).orElseGet(() -> insertNewUser(jwt, authSubject));
  }

  /**
   * Two concurrent first logins for the same subject can both miss the lookup above and both try to
   * insert; the DB's unique constraint on {@code auth_subject} (not this method) is what actually
   * prevents a duplicate row, so the loser here must fall back to a re-read rather than fail the
   * request.
   */
  private User insertNewUser(Jwt jwt, String authSubject) {
    try {
      return users.save(
          new User(authSubject, resolveEmail(jwt, authSubject), resolveName(jwt, authSubject)));
    } catch (DataIntegrityViolationException raceLost) {
      return users.findByAuthSubject(authSubject).orElseThrow(() -> raceLost);
    }
  }

  /**
   * Standard Auth0 access tokens for a custom API audience carry no profile claims by default —
   * only {@link #EMAIL_CLAIM}, deliberately placed there by an Auth0 Action (docs/MANUAL_SETUP.md
   * Gate H1), counts as verified. Without it (Gate H1 not yet completed, or the local/CI test
   * issuer), fall back to a deterministic, clearly-synthetic placeholder scoped to the subject —
   * never a value this method merely guesses at being real.
   */
  private static String resolveEmail(Jwt jwt, String authSubject) {
    String claim = jwt.getClaimAsString(EMAIL_CLAIM);
    if (claim != null && !claim.isBlank()) {
      return claim;
    }
    return placeholderHandle(authSubject) + "@users.opspilot.local";
  }

  private static String resolveName(Jwt jwt, String authSubject) {
    String claim = jwt.getClaimAsString(NAME_CLAIM);
    if (claim != null && !claim.isBlank()) {
      return claim;
    }
    return placeholderHandle(authSubject);
  }

  private static String placeholderHandle(String authSubject) {
    return authSubject.replaceAll("[^a-zA-Z0-9]+", "-");
  }
}
