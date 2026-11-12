package com.opspilot.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the real {@link SecurityConfig} through the full Spring MVC filter chain: only the two
 * health probes are reachable without authentication; everything else requires a valid JWT (T07 —
 * {@code anyRequest().authenticated()} replaced T02's blanket {@code denyAll()}, so an
 * unauthenticated request now gets 401, not 403). Unlike {@code GlobalExceptionHandlerTest},
 * filters are NOT disabled here.
 *
 * <p>Needs a real Postgres via Testcontainers as of T07: {@link SecurityConfig} now depends on
 * {@code UserRepository} to resolve principals, so even this filter-chain-level test can no longer
 * exclude datasource/JPA/Flyway the way T02's version of this test did.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Tag("security")
class SecurityConfigIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void livenessProbeIsPubliclyReachable() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  void readinessProbeIsPubliclyReachable() throws Exception {
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }

  @Test
  void everyOtherPathRequiresAuthenticationNotSilentlyPermitted() throws Exception {
    mockMvc.perform(get("/anything-else")).andExpect(status().isUnauthorized());
  }

  @Test
  void unexposedActuatorEndpointsAreNotReachableEither() throws Exception {
    mockMvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
  }
}
