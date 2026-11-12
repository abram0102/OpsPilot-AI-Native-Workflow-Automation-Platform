package com.opspilot.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import com.opspilot.agent.AgentRunStatus;
import com.opspilot.auth.User;
import com.opspilot.auth.UserRepository;
import com.opspilot.common.security.TestIssuerConfig;
import com.opspilot.common.security.TestJwtSigner;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The metrics surface (docs/TASKS.md T30; docs/SECURITY_AND_TESTING.md §7).
 *
 * <p>Two separate claims, and the security one comes first because it is the one that would matter
 * if it were wrong. Metrics name internal structure — endpoint templates, queue names, error codes
 * — and a run-status distribution is a business figure. Exposing the endpoint under {@code
 * /actuator} is not the same as making it public, and this pins the difference.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
// Spring Boot disables metrics export in tests by default, so without this the endpoint simply
// does not exist here and every assertion below would be testing the 404 handler.
@AutoConfigureMetrics
@AutoConfigureMockMvc
@Tag("security")
class MetricsEndpointIntegrationTest {

  private static final String AUDIENCE = "https://api.opspilot.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private KeyPair testIssuerKeyPair;
  @Autowired private UserRepository users;

  @Test
  void theMetricsEndpointIsNotPubliclyReachable() throws Exception {
    // The health probes are public because a load balancer needs them without a credential.
    // Nothing else under /actuator is, and that includes the one this ticket just added.
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void anAuthenticatedCallerCanScrapeIt() throws Exception {
    String body = scrape();
    assertThat(body).isNotBlank();
  }

  @Test
  void itCarriesTheRequestLatencyHistogramTheSpecificationAsksFor() throws Exception {
    // §7 asks for "API request rate / p50/p95 / 5xx". Micrometer's own http.server.requests
    // provides all three; this asserts it is actually present rather than assumed, because the
    // dashboards in docs/OBSERVABILITY.md are written against this metric name.
    String body = scrape();
    assertThat(body).contains("http_server_requests_seconds");
  }

  @Test
  void itCarriesTheBusinessMetricsThisTicketAdded() throws Exception {
    String body = scrape();

    // One series per run status, tagged rather than named — so a dashboard can sum or filter, and
    // a status added later needs no new metric name.
    assertThat(body).contains("opspilot_agent_runs");
    for (AgentRunStatus status : AgentRunStatus.values()) {
      assertThat(body)
          .as("a series for every run status, so a dashboard can show the whole distribution")
          .contains("status=\"" + status.name() + "\"");
    }

    assertThat(body).contains("opspilot_approvals_oldest_pending_seconds");
    assertThat(body).contains("opspilot_outbox_events");
    assertThat(body).contains("status=\"DEAD\"");
  }

  @Test
  void noMetricNamesATenantOrAUser() throws Exception {
    // A gauge tagged by organization would turn the operator's metrics endpoint into a
    // cross-tenant data source, and a high-cardinality one at that. The tags here are statuses,
    // which are a fixed, small set.
    String body = scrape();

    assertThat(body).doesNotContain("organization_id=");
    assertThat(body).doesNotContain("organizationId");
    assertThat(body).doesNotContain("user_id=");
  }

  private String scrape() throws Exception {
    User operator = users.saveAndFlush(user("metrics-reader"));
    return mockMvc
        .perform(
            get("/actuator/prometheus")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(operator)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String tokenFor(User user) {
    return TestJwtSigner.sign(
        testIssuerKeyPair,
        TestIssuerConfig.TEST_ISSUER,
        user.getAuthSubject(),
        AUDIENCE,
        Instant.now().plus(10, ChronoUnit.MINUTES));
  }

  private static User user(String label) {
    String unique = UUID.randomUUID().toString();
    return new User("auth0|" + label + "-" + unique, label + "-" + unique + "@example.test", label);
  }
}
