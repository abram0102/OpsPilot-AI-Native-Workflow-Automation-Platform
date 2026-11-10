package com.opspilot.agent.internal;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The private, machine-only action endpoint (docs/API_CONTRACT.md "Private internal service API").
 *
 * <p>Reachable only through {@link com.opspilot.common.security.InternalServiceSecurityConfig}'s
 * filter chain, which accepts a service-audience token signed with the shared worker secret and
 * nothing else. A browser's user token fails there on both signature and audience.
 *
 * <p>Excluded from the published OpenAPI contract on purpose (`springdoc.paths-to-exclude`): that
 * spec generates the browser's typed client, and this is an endpoint no browser may call. See
 * docs/DECISIONS.md ADR-045 — and the test that asserts the exclusion still holds.
 *
 * <p>No {@code orgId} path variable, unlike every public controller: the organization is a property
 * of the persisted run, not of the request.
 */
@RestController
@RequestMapping("/internal/v1/agent-actions")
@Tag(name = "Internal agent actions")
class InternalAgentActionController {

  private final AgentActionExecutionService executionService;

  InternalAgentActionController(AgentActionExecutionService executionService) {
    this.executionService = executionService;
  }

  /**
   * Always 200 with a canonical body, including for a refusal.
   *
   * <p>A rejected action is a normal outcome the worker must record, not a transport error — and
   * the three-way {@code status} is what it branches on. Returning 403 here would conflate "you may
   * not do this" with "your identity is wrong", which the filter chain already answers.
   */
  @PostMapping("/execute")
  ExecuteAgentActionResponse execute(@Valid @RequestBody ExecuteAgentActionRequest request) {
    return executionService.execute(request);
  }
}
