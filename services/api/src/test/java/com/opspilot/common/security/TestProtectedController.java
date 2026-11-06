package com.opspilot.common.security;

import com.opspilot.auth.AuthenticatedPrincipal;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint requiring nothing beyond valid authentication (no role check) — used solely to
 * prove the JWT validation pipeline itself accepts or rejects a token. Never shipped in the
 * production jar.
 */
@RestController
class TestProtectedController {

  @GetMapping("/__test/protected")
  Map<String, String> protectedEndpoint(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
    return Map.of("authSubject", principal.authSubject());
  }
}
