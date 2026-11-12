package com.opspilot.common.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
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
@Tag("security")
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

  // ---- T28: client mistakes must not read as server failures -------------------------------
  //
  // Every case below used to fall through to the catch-all `Exception` handler and answer 500.
  // That is wrong twice: the caller is told to retry something that will never work, and a
  // malformed request raises the 5xx rate that docs/SECURITY_AND_TESTING.md §7 alerts on — so a
  // scanner probing the API would look exactly like an outage. Found by
  // SecurityRegressionSweepIntegrationTest on a real endpoint
  // (PUT .../knowledge/uploads/local-object, whose objectKey is required).

  @Test
  void aMissingRequiredQueryParameterIsAClientErrorNotAServerFailure() throws Exception {
    mockMvc
        .perform(get("/__test/echo-param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.error.details[0]").value("required: parameter is required."));
  }

  @Test
  void aSuppliedQueryParameterStillReachesTheHandler() throws Exception {
    mockMvc.perform(get("/__test/echo-param").param("required", "ok")).andExpect(status().isOk());
  }

  @Test
  void theWrongHttpMethodIs405RatherThan500() throws Exception {
    mockMvc
        .perform(delete("/__test/echo"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
  }

  @Test
  void anUnsupportedContentTypeIs415RatherThan500() throws Exception {
    mockMvc
        .perform(post("/__test/echo").contentType(MediaType.TEXT_PLAIN).content("name=x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void theErrorEnvelopeNeverCarriesAnExceptionClassNameOrStackFrame() throws Exception {
    // AGENTS.md: "sensitive internals remain private". The handlers above build their own
    // messages; this asserts none of them leaked the exception's own text instead.
    String body =
        mockMvc.perform(get("/__test/echo-param")).andReturn().getResponse().getContentAsString();

    org.assertj.core.api.Assertions.assertThat(body)
        .doesNotContain("Exception")
        .doesNotContain("org.springframework")
        .doesNotContain("com.opspilot");
  }
}
