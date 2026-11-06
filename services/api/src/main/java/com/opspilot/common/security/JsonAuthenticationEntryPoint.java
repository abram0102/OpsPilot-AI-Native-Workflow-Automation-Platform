package com.opspilot.common.security;

import com.opspilot.common.web.ApiErrorResponse;
import com.opspilot.common.web.RequestIdSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the 401 response on the same error envelope as everything else (docs/API_CONTRACT.md:
 * {@code UNAUTHENTICATED}) instead of Spring Security's default empty-body challenge response —
 * this runs at the servlet-filter level, before any controller/{@link
 * com.opspilot.common.web.GlobalExceptionHandler} involvement is possible.
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ApiErrorResponse body =
        ApiErrorResponse.of(
            "UNAUTHENTICATED",
            "Authentication is required.",
            RequestIdSupport.resolve(request),
            List.of());
    objectMapper.writeValue(response.getWriter(), body);
  }
}
