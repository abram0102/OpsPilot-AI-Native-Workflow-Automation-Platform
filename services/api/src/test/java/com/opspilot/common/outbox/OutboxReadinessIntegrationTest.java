package com.opspilot.common.outbox;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T24 acceptance: "outbox stays durable when Redis unavailable" — from the API's point of view.
 *
 * <p>No Redis container, and {@code spring.data.redis.port} aimed at a closed port, so Redis is
 * genuinely unreachable for the whole context rather than mocked into looking unreachable.
 *
 * <p>The property under test is that an unreachable queue is a <em>delivery delay</em>, not an
 * outage. If Redis were in the readiness group, a Redis restart would take every API instance out
 * of rotation and turn a few seconds of queued events into real downtime for users who are not
 * waiting on an agent run at all.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "spring.data.redis.host=127.0.0.1",
      // Port 1 is never a Redis. Connection refused is immediate, so this test does not sit on a
      // connect timeout.
      "spring.data.redis.port=1",
      "spring.data.redis.connect-timeout=200ms",
      "spring.data.redis.timeout=200ms"
    })
class OutboxReadinessIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void readinessStaysUpWhileRedisIsUnreachable() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void livenessStaysUpWhileRedisIsUnreachable() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void theOutageIsStillVisibleInTheAggregateHealthDocument() throws Exception {
    // The counterpart to the two tests above, and the reason Redis was not simply excluded from
    // health altogether: operators must be able to see that the queue is down. Only the
    // orchestrator's probes are insulated from it. docs/API_CONTRACT.md names the two probe paths
    // as the health contract; docs/DEPLOYMENT_RUNBOOK.md's plain /actuator/health is the
    // developer's fuller view, and it is this one that turns red.
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"));
  }
}
