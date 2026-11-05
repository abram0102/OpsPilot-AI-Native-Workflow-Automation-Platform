package com.opspilot.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the real {@link SecurityConfig} through the full Spring MVC
 * filter chain, proving T02's "no debug auth bypass" acceptance check: only
 * the two health probes are reachable; everything else is denied, not
 * silently permitted. Unlike {@code GlobalExceptionHandlerTest}, filters are
 * NOT disabled here — this test is specifically about the security filter
 * chain's behavior.
 *
 * <p>This ticket has no business endpoints or database schema yet, so
 * datasource/JPA/Flyway auto-configuration is excluded for this test only —
 * nothing in the production app is changed by that; it just lets the full
 * context start without a live Postgres, which isn't available in every
 * environment that runs this suite (see docs/PROGRESS.md T01).
 */
@SpringBootTest(
        properties = {
            // Spring Boot 4 moved these out of org.springframework.boot.autoconfigure.* into
            // per-module packages; verified against the actual 4.1.2 jars (see
            // docs/DEPENDENCY_DECISIONS.md T02 notes).
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
        })
@AutoConfigureMockMvc
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
    void everyOtherPathIsDeniedNotSilentlyPermitted() throws Exception {
        mockMvc.perform(get("/anything-else")).andExpect(status().isForbidden());
    }

    @Test
    void unexposedActuatorEndpointsAreNotReachableEither() throws Exception {
        mockMvc.perform(get("/actuator")).andExpect(status().isForbidden());
    }
}
