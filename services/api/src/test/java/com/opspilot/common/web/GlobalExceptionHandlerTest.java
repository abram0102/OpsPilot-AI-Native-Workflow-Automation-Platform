package com.opspilot.common.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the error envelope documented in docs/API_CONTRACT.md for the two failure modes a
 * skeleton with no business endpoints yet can still exercise: malformed JSON and bean-validation
 * failures. Uses a test-only controller (see {@link TestEchoController}), never a production
 * endpoint.
 *
 * <p>{@code addFilters = false} disables all servlet filters for this slice (including Spring
 * Security's default auto-configured chain, which would otherwise 401 every request before it
 * reaches the controller) so this test is purely about exception-to-response mapping, not
 * authorization — that is covered separately by {@code SecurityConfigIntegrationTest}.
 */
@WebMvcTest(controllers = TestEchoController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

  private static final String UUID_PATTERN =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  @Autowired private MockMvc mockMvc;

  @Test
  void malformedJsonReturnsConsistentValidationErrorEnvelope() throws Exception {
    mockMvc
        .perform(
            post("/__test/echo").contentType(MediaType.APPLICATION_JSON).content("{not-valid-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.error.message").isNotEmpty())
        .andExpect(jsonPath("$.error.requestId").value(matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.error.details").isArray());
  }

  @Test
  void beanValidationFailureReturnsConsistentValidationErrorEnvelope() throws Exception {
    mockMvc
        .perform(
            post("/__test/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.error.requestId").value(matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.error.details[0]").value("name: must not be blank"));
  }

  @Test
  void validRequestIsNotTouchedByTheErrorHandler() throws Exception {
    mockMvc
        .perform(
            post("/__test/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Acme Demo\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Acme Demo"));
  }
}
